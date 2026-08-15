package plus.maa.backend.repository.ktorm

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import plus.maa.backend.cache.InternalComposeCache
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.service.EmailService
import plus.maa.backend.service.UserDetailServiceImpl
import plus.maa.backend.service.UserService
import plus.maa.backend.service.jwt.JwtService
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * UserRepository 的真实数据库集成测试（zonky embedded-postgres + Flyway 迁移）。
 *
 * 覆盖方法（全部 public 方法，含继承的 findAll/count）：
 * findByEmail / existsByUserName / findAllById / findById / deleteById / existsById / findAll / count /
 * createFromMaaUser / insertEntity / updateEntity / save / follow / unfollow / follows / fans /
 * getFollowUpdatedAtMap / findFollow / setSpecialFollow / getSpecialFollowedTargetIds /
 * getSpecialFollowerIds / getFansUpdatedAtMap / getFollowedTargetIds / getFollowerTargetIds /
 * isFollowing / isSpecialFollowing / searchByUserName。
 *
 * 原生查询点（UserService 层）：
 * - UserService.findByUserIdOrDefault、UserService.get、UserService.search
 *   通过真实 UserService 实例直接覆盖 —— 仅用 mockk 替换 EmailService/PasswordEncoder 两个与查询无关的
 *   重依赖，其余（repository/UserDetailServiceImpl/JwtService）均为真实实现。
 * - InternalComposeCache.getMaaUserCache 缓存 UserEntity 的路径经 UserService.findByUserIdOrDefaultInCache
 *   覆盖（见 findByUserIdOrDefaultInCacheCachesAndInvalidates）。
 *
 * 未覆盖点及原因：
 * - UserService 其余方法（login/register/modifyPassword/getMe 等）依赖 Redis 验证码、邮件发送、
 *   Spring Security 上下文，不属于 repository 基线范围，由服务层测试另行覆盖。
 * - 事务边界：手动 new 出的 repository 没有 Spring AOP 代理，@Transactional 不覆盖 Jdbi；
 *   follow/unfollow 的原子性由内部 jdbi.useTransaction 保证（见 follow_rollsBackAllStatementsWhenLaterStatementFails）。
 *
 * 行为记录：
 * 1. follow/unfollow 幂等：重复 follow 不重复插行、计数不重复加；未关注时 unfollow 为 no-op。
 * 2. 自关注（userId==followUserId）被允许：user_follow 插入 (1,1)，following_count 与 fans_count 各自 +1。
 * 3. follow 不存在的用户：user_follow 照常插入、fans 计数 UPDATE 0 行不报错（无外键约束）。
 * 4. setSpecialFollow 对相同值重复设置仍返回 true（PG UPDATE 按 WHERE 匹配行计数，不比较新旧值）。
 * 5. save 显式指定不存在的非零主键（如 999）时按给定值插入；
 *    **显式赋 0 的主键自增回填**（data class 无法区分「显式赋 0」与「未赋值」，见 save_explicitZeroIdBackfillsId）。
 * 6. pwd_update_time 为 timestamp(3)（无时区），毫秒以下精度被数据库舍入，读回与写入差 < 1ms。
 * 7. findByEmail 等值比较区分大小写（PG text `=`）。
 * 8. user_name 无唯一约束，重复 userName 可共存。
 * 9. UserService.search 的 LIKE 通配符 `%`/`_` 已转义，搜索词按字面匹配。
 * 10. findByUserIdOrDefaultInCache 会把 UNKNOWN 也写入缓存。
 * 11. follows/fans 返回 Page（COUNT + LIMIT/OFFSET、无 ORDER BY，与基线 paginate 语义一致）。
 */
class UserRepositoryTest : TestDbSupport() {

    private val repository = UserRepository(jdbi)

    /** 真实 UserService（仅 EmailService/PasswordEncoder 为 mock，查询路径不触及）。 */
    private val userService: UserService by lazy {
        UserService(
            userRepository = repository,
            emailService = mockk<EmailService>(relaxed = true),
            passwordEncoder = mockk<PasswordEncoder>(relaxed = true),
            userDetailService = UserDetailServiceImpl(repository),
            jwtService = JwtService(MaaCopilotProperties()),
        )
    }

    // ------------------------------------------------------------------
    // 数据准备 helpers（独立于被测 repository，经 Jdbi 直接写表）
    // ------------------------------------------------------------------

    /** 插入一个用户，userId 走自增。时间戳保持毫秒精度，避免 timestamp(3) 舍入歧义。 */
    private fun addUser(
        userName: String,
        email: String,
        status: Int = 1,
        pwdUpdateTime: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        followingCount: Int = 0,
        fansCount: Int = 0,
    ): UserEntity {
        val user = UserEntity(
            userName = userName,
            email = email,
            password = "password-of-$userName",
            status = status,
            pwdUpdateTime = pwdUpdateTime,
            followingCount = followingCount,
            fansCount = fansCount,
        )
        repository.insertEntity(user)
        return user
    }

    /** 直接写一条 user_follow（不要求用户存在，表无外键约束）。 */
    private fun addFollow(
        userId: Long,
        followUserId: Long,
        specialFollow: Boolean = false,
        updatedAt: LocalDateTime = LocalDateTime.of(2025, 1, 1, 0, 0, 0),
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO user_follow (user_id, follow_user_id, special_follow, updated_at)
                VALUES (:userId, :followUserId, :specialFollow, :updatedAt)
                """.trimIndent(),
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .bind("specialFollow", specialFollow)
                .bind("updatedAt", updatedAt)
                .execute()
        }
    }

    private fun countFollow(userId: Long, followUserId: Long): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            "SELECT COUNT(*) FROM user_follow WHERE user_id = :userId AND follow_user_id = :followUserId",
        )
            .bind("userId", userId)
            .bind("followUserId", followUserId)
            .mapTo(Long::class.java)
            .one()
            .toInt()
    }

    private fun totalFollowRows(): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*) FROM user_follow")
            .mapTo(Long::class.java)
            .one()
            .toInt()
    }

    private fun executeSql(sql: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    private fun findPsqlException(t: Throwable): PSQLException? =
        generateSequence(t) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()

    private fun assertUniqueConstraintViolation(block: () -> Unit) {
        val ex = assertThrows(Exception::class.java) { block() }
        val psql = findPsqlException(ex)
        assertNotNull(psql, "异常链中应包含 PSQLException，实际=${ex::class.simpleName}: ${ex.message}")
        assertEquals("23505", psql!!.sqlState)
    }

    // ------------------------------------------------------------------
    // findByEmail
    // ------------------------------------------------------------------

    @Test
    fun findByEmail_hitAndMiss() {
        addUser("alice", "alice@example.com")

        val found = repository.findByEmail("alice@example.com")
        assertNotNull(found)
        assertEquals("alice", found!!.userName)
        assertNull(repository.findByEmail("nobody@example.com"))
        // 基线：PG text 等值比较区分大小写
        assertNull(repository.findByEmail("Alice@example.com"))
    }

    // ------------------------------------------------------------------
    // existsByUserName
    // ------------------------------------------------------------------

    @Test
    fun existsByUserName_trueFalseAndDuplicatesAllowed() {
        // 基线：user_name 无唯一约束（仅普通索引），重复 userName 可共存
        addUser("dup", "dup-1@example.com")
        addUser("dup", "dup-2@example.com")

        assertTrue(repository.existsByUserName("dup"))
        assertFalse(repository.existsByUserName("nope"))
        assertEquals(2, repository.count())
    }

    // ------------------------------------------------------------------
    // findAllById
    // ------------------------------------------------------------------

    @Test
    fun findAllById_partialHitAndEmptyGuard() {
        val a = addUser("a", "a@example.com")
        val b = addUser("b", "b@example.com")
        addUser("c", "c@example.com")

        // 部分命中：不存在的 id 被忽略，顺序不保证 → 按集合比较
        val hit = repository.findAllById(listOf(a.userId, b.userId, 999L))
        assertEquals(setOf(a.userId, b.userId), hit.map { it.userId }.toSet())

        assertTrue(repository.findAllById(emptyList<Long>()).isEmpty())

        // 全部不存在 → 空列表
        assertTrue(repository.findAllById(listOf(888L, 999L)).isEmpty())
    }

    // ------------------------------------------------------------------
    // findById / deleteById / existsById / findAll / count（含原继承方法）
    // ------------------------------------------------------------------

    @Test
    fun findById_hitMissAndZero() {
        val a = addUser("find-me", "find-me@example.com")

        assertEquals(a.userId, repository.findById(a.userId)!!.userId)
        assertNull(repository.findById(999L))
        assertNull(repository.findById(0L))
    }

    @Test
    fun deleteById_affectedRowsSemantics() {
        val a = addUser("delete-me", "delete-me@example.com")

        assertTrue(repository.deleteById(a.userId))
        assertNull(repository.findById(a.userId))
        assertFalse(repository.deleteById(a.userId))
        assertFalse(repository.deleteById(0L))
        assertFalse(repository.deleteById(999L))
    }

    @Test
    fun existsById_trueFalse() {
        val a = addUser("exists-me", "exists-me@example.com")

        assertTrue(repository.existsById(a.userId))
        assertFalse(repository.existsById(999L))
        assertFalse(repository.existsById(0L))
    }

    @Test
    fun findAllAndCount_emptyAndPopulated() {
        assertEquals(0, repository.count())
        assertTrue(repository.findAll().isEmpty())

        addUser("f1", "f1@example.com")
        addUser("f2", "f2@example.com")

        assertEquals(2, repository.count())
        assertEquals(2, repository.findAll().size)
    }

    // ------------------------------------------------------------------
    // createFromMaaUser
    // ------------------------------------------------------------------

    @Test
    fun createFromMaaUser_copiesFieldsAndKeepsUserIdZero() {
        val maaUser = MaaUser(
            userId = "42", // 基线：userId 不搬运，实体 userId 保持 0（新实体）
            userName = "maa-name",
            email = "maa@example.com",
            password = "maa-pwd",
            status = 1,
            pwdUpdateTime = Instant.parse("2024-04-04T04:04:04.456Z"),
            followingCount = 6,
            fansCount = 8,
        )

        val entity = repository.createFromMaaUser(maaUser)

        assertEquals(0L, entity.userId)
        assertEquals("maa-name", entity.userName)
        assertEquals("maa@example.com", entity.email)
        assertEquals("maa-pwd", entity.password)
        assertEquals(1, entity.status)
        assertEquals(Instant.parse("2024-04-04T04:04:04.456Z"), entity.pwdUpdateTime)
        assertEquals(6, entity.followingCount)
        assertEquals(8, entity.fansCount)
    }

    // ------------------------------------------------------------------
    // insertEntity
    // ------------------------------------------------------------------

    @Test
    fun insertEntity_backfillsIdAndRoundTripsAllColumns() {
        val entity = repository.insertEntity(
            UserEntity(
                userName = "insert-entity",
                email = "insert@example.com",
                password = "pwd",
                status = 1,
                pwdUpdateTime = Instant.parse("2024-02-02T02:02:02.123Z"),
                followingCount = 3,
                fansCount = 4,
            ),
        )

        assertTrue(entity.userId > 0, "bigserial 主键应回填")
        val loaded = repository.findById(entity.userId)!!
        assertEquals(entity.userId, loaded.userId)
        assertEquals("insert-entity", loaded.userName)
        assertEquals("insert@example.com", loaded.email)
        assertEquals("pwd", loaded.password)
        assertEquals(1, loaded.status)
        assertEquals(Instant.parse("2024-02-02T02:02:02.123Z"), loaded.pwdUpdateTime)
        assertEquals(3, loaded.followingCount)
        assertEquals(4, loaded.fansCount)
    }

    @Test
    fun insertEntity_consecutiveIdsIncrementFromOne() {
        repeat(3) {
            repository.insertEntity(
                UserEntity(
                    userName = "seq-$it",
                    email = "seq-$it@example.com",
                    password = "pwd",
                    status = 0,
                    pwdUpdateTime = Instant.parse("2024-01-01T00:00:00Z"),
                    followingCount = 0,
                    fansCount = 0,
                ),
            )
        }
        assertEquals(listOf(1L, 2L, 3L), repository.findAll().map { it.userId }.sorted())
    }

    @Test
    fun insertEntity_duplicateEmailThrowsUniqueConstraint() {
        addUser("first", "same@example.com")
        val second = UserEntity(
            userName = "second",
            email = "same@example.com", // 命中唯一索引 idx_user_user_email
            password = "pwd",
            status = 0,
            pwdUpdateTime = Instant.parse("2024-01-01T00:00:00Z"),
            followingCount = 0,
            fansCount = 0,
        )
        assertUniqueConstraintViolation { repository.insertEntity(second) }
        assertEquals(1, repository.count())
    }

    @Test
    fun insertEntity_pwdUpdateTimeTruncatedToTimestamp3Millis() {
        // 基线：pwd_update_time 为 timestamp(3) 无时区列，毫秒以下精度被数据库舍入
        val subMillis = Instant.parse("2024-03-03T03:03:03.123456789Z")
        val entity = repository.insertEntity(
            UserEntity(
                userName = "precision",
                email = "precision@example.com",
                password = "pwd",
                status = 0,
                pwdUpdateTime = subMillis,
                followingCount = 0,
                fansCount = 0,
            ),
        )

        val loaded = repository.findById(entity.userId)!!
        val diffNanos = ChronoUnit.NANOS.between(subMillis, loaded.pwdUpdateTime)
        assertTrue(Math.abs(diffNanos) < 1_000_000L, "读回与写入应相差 <1ms，实际 diff=${diffNanos}ns")
    }

    // ------------------------------------------------------------------
    // updateEntity（迁移后为全列 SET；断言与基线 flushChanges 场景一致）
    // ------------------------------------------------------------------

    @Test
    fun updateEntity_onlyChangedColumnsAreFlushed() {
        val user = addUser(
            userName = "flush",
            email = "flush@example.com",
            status = 1,
            followingCount = 5,
            fansCount = 7,
        )
        val loaded = repository.findById(user.userId)!!
        loaded.status = 2

        repository.updateEntity(loaded)

        val re = repository.findById(user.userId)!!
        assertEquals(2, re.status)
        assertEquals("flush", re.userName)
        assertEquals("flush@example.com", re.email)
        assertEquals("password-of-flush", re.password)
        assertEquals(5, re.followingCount)
        assertEquals(7, re.fansCount)
        assertEquals(user.pwdUpdateTime, re.pwdUpdateTime)
    }

    @Test
    fun updateEntity_allFieldsRoundTrip() {
        val user = addUser("upd-all", "upd-all@example.com")
        val loaded = repository.findById(user.userId)!!
        loaded.userName = "upd-all-new"
        loaded.password = "new-pwd"
        loaded.status = 2
        loaded.pwdUpdateTime = Instant.parse("2025-05-05T05:05:05.555Z")
        loaded.followingCount = 11
        loaded.fansCount = 22

        repository.updateEntity(loaded)

        val re = repository.findById(user.userId)!!
        assertEquals("upd-all-new", re.userName)
        assertEquals("upd-all@example.com", re.email)
        assertEquals("new-pwd", re.password)
        assertEquals(2, re.status)
        assertEquals(Instant.parse("2025-05-05T05:05:05.555Z"), re.pwdUpdateTime)
        assertEquals(11, re.followingCount)
        assertEquals(22, re.fansCount)
    }

    // ------------------------------------------------------------------
    // save
    // ------------------------------------------------------------------

    @Test
    fun save_newEntityInsertsAndBackfillsId() {
        val user = UserEntity(
            userName = "save-new",
            email = "save-new@example.com",
            password = "pwd",
            status = 1,
            pwdUpdateTime = Instant.parse("2024-01-01T00:00:00Z"),
            followingCount = 0,
            fansCount = 0,
        )

        repository.save(user)

        assertTrue(user.userId > 0, "新实体应插入并回填自增 id")
        assertEquals(1, repository.count())
        assertNotNull(repository.findById(user.userId))
    }

    @Test
    fun save_explicitNonExistentIdInsertsThatId() {
        val user = UserEntity(
            userId = 999L,
            userName = "save-explicit",
            email = "save-explicit@example.com",
            password = "pwd",
            status = 1,
            pwdUpdateTime = Instant.parse("2024-01-01T00:00:00Z"),
            followingCount = 0,
            fansCount = 0,
        )

        repository.save(user)

        assertEquals(999L, user.userId)
        assertNotNull(repository.findById(999L))
        assertEquals(1, repository.count())
    }

    @Test
    fun save_explicitZeroIdBackfillsId() {
        // data class 无法区分「显式赋 0」与「未赋值」，userId==0 一律视为新实体 → 自增回填。
        val user = UserEntity(
            userId = 0L,
            userName = "save-zero",
            email = "save-zero@example.com",
            password = "pwd",
            status = 1,
            pwdUpdateTime = Instant.parse("2024-01-01T00:00:00Z"),
            followingCount = 0,
            fansCount = 0,
        )

        repository.save(user)

        assertTrue(user.userId > 0, "userId==0 应自增回填而非按 0 插入")
        assertEquals(1, repository.count())
        assertNull(repository.findById(0L), "user_id=0 的行不再存在")
    }

    @Test
    fun save_existingEntityUpdatesWithoutNewRow() {
        val user = addUser("save-existing", "save-existing@example.com")
        val loaded = repository.findById(user.userId)!!
        loaded.fansCount = 42

        repository.save(loaded)

        assertEquals(1, repository.count(), "已存在实体应 UPDATE 而非新增行")
        assertEquals(42, repository.findById(user.userId)!!.fansCount)
    }

    // ------------------------------------------------------------------
    // follow / unfollow（事务多语句）
    // ------------------------------------------------------------------

    @Test
    fun follow_insertsRowAndIncrementsBothCounters() {
        val a = addUser("follower", "follower@example.com")
        val b = addUser("followed", "followed@example.com")

        repository.follow(a.userId, b.userId)

        assertEquals(1, countFollow(a.userId, b.userId))
        assertEquals(1, repository.findById(a.userId)!!.followingCount)
        assertEquals(1, repository.findById(b.userId)!!.fansCount)
        val row = repository.findFollow(a.userId, b.userId)!!
        assertFalse(row.specialFollow, "新关注默认 special_follow=false")
        val now = LocalDateTime.now()
        assertTrue(row.updatedAt.isAfter(now.minusMinutes(1)), "updated_at 应≈当前时间")
        assertTrue(row.updatedAt.isBefore(now.plusMinutes(1)), "updated_at 应≈当前时间")
    }

    @Test
    fun follow_isIdempotent() {
        val a = addUser("idem", "idem@example.com")
        val b = addUser("idem-b", "idem-b@example.com")

        repository.follow(a.userId, b.userId)
        repository.follow(a.userId, b.userId) // 重复 follow：check-then-act 直接 return

        assertEquals(1, totalFollowRows(), "重复 follow 不应重复插行")
        assertEquals(1, countFollow(a.userId, b.userId))
        assertEquals(1, repository.findById(a.userId)!!.followingCount)
        assertEquals(1, repository.findById(b.userId)!!.fansCount)
    }

    @Test
    fun follow_selfFollowIsAllowedAndCountsBothSides() {
        // 基线：自关注被允许 —— 插入 (1,1)，following_count 与 fans_count 各自 +1（同一行被两个 UPDATE 命中）
        val a = addUser("self", "self@example.com")

        repository.follow(a.userId, a.userId)

        assertEquals(1, countFollow(a.userId, a.userId))
        assertEquals(1, repository.findById(a.userId)!!.followingCount)
        assertEquals(1, repository.findById(a.userId)!!.fansCount)
    }

    @Test
    fun follow_nonExistentUserInsertsRowWithoutFkError() {
        // 基线：user_follow 无外键约束 —— 关注不存在的用户照常插行，fans 计数 UPDATE 0 行不报错
        val a = addUser("lonely", "lonely@example.com")

        repository.follow(a.userId, 999L)

        assertEquals(1, countFollow(a.userId, 999L))
        assertEquals(1, repository.findById(a.userId)!!.followingCount)
        assertNull(repository.findById(999L))
    }

    @Test
    fun follow_rollsBackAllStatementsWhenLaterStatementFails() {
        // 验证：follow 的 检查→插入→双计数更新 必须原子提交/回滚
        // （内部 jdbi.useTransaction 保证；此处用 TransactionTemplate 模拟外层 Spring 事务边界）。
        val a = addUser("tx-a", "tx-a@example.com")
        val b = addUser("tx-b", "tx-b@example.com")

        executeSql(
            """
            CREATE OR REPLACE FUNCTION force_fail_on_user_update() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced failure for rollback test';
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        executeSql("DROP TRIGGER IF EXISTS trg_force_fail_user_update ON \"user\"")
        executeSql(
            "CREATE TRIGGER trg_force_fail_user_update BEFORE UPDATE ON \"user\" " +
                "FOR EACH ROW EXECUTE FUNCTION force_fail_on_user_update()",
        )
        try {
            val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
            val ex = assertThrows(Exception::class.java) {
                tx.execute<Unit> { repository.follow(a.userId, b.userId) }
            }
            // 异常链应包含 PSQLException（Jdbi 包装后 cause 链保留原始 SQL 异常）
            val psql = findPsqlException(ex)
            if (psql != null) {
                assertEquals("P0001", psql.sqlState)
            }
            // 回滚后：user_follow 无残留行，双方计数不变（无论异常形态如何，事务必须整体回滚）
            assertEquals(0, countFollow(a.userId, b.userId))
            assertEquals(0, repository.findById(a.userId)!!.followingCount)
            assertEquals(0, repository.findById(b.userId)!!.fansCount)
        } finally {
            executeSql("DROP TRIGGER IF EXISTS trg_force_fail_user_update ON \"user\"")
            executeSql("DROP FUNCTION IF EXISTS force_fail_on_user_update()")
        }
    }

    @Test
    fun unfollow_deletesRowAndDecrementsBothCounters() {
        val a = addUser("uf", "uf@example.com")
        val b = addUser("uf-b", "uf-b@example.com")
        repository.follow(a.userId, b.userId)

        repository.unfollow(a.userId, b.userId)

        assertEquals(0, countFollow(a.userId, b.userId))
        assertEquals(0, repository.findById(a.userId)!!.followingCount)
        assertEquals(0, repository.findById(b.userId)!!.fansCount)
    }

    @Test
    fun unfollow_isIdempotentWhenNotFollowing() {
        val a = addUser("uf-idem", "uf-idem@example.com")
        val b = addUser("uf-idem-b", "uf-idem-b@example.com")

        // 未关注时调用：no-op，不抛异常、不产生负计数
        repository.unfollow(a.userId, b.userId)

        assertEquals(0, totalFollowRows())
        assertEquals(0, repository.findById(a.userId)!!.followingCount)
        assertEquals(0, repository.findById(b.userId)!!.fansCount)
    }

    @Test
    fun unfollow_thenFollowReestablishesRelationship() {
        val a = addUser("uf-re", "uf-re@example.com")
        val b = addUser("uf-re-b", "uf-re-b@example.com")

        repository.follow(a.userId, b.userId)
        repository.unfollow(a.userId, b.userId)
        repository.follow(a.userId, b.userId)

        assertEquals(1, countFollow(a.userId, b.userId))
        assertEquals(1, totalFollowRows())
        assertEquals(1, repository.findById(a.userId)!!.followingCount)
        assertEquals(1, repository.findById(b.userId)!!.fansCount)
    }

    // ------------------------------------------------------------------
    // follows / fans（IN 子查询 + 分页，返回 Page）
    // ------------------------------------------------------------------

    @Test
    fun follows_returnsFollowedUsersIncludingSpecial() {
        val a = addUser("fa", "fa@example.com")
        val b = addUser("fb", "fb@example.com")
        val c = addUser("fc", "fc@example.com")
        repository.follow(a.userId, b.userId)
        repository.follow(a.userId, c.userId)
        repository.setSpecialFollow(a.userId, c.userId, true)

        val followed = repository.follows(a.userId, PageRequest.of(0, 10)).content

        assertEquals(setOf(b.userId, c.userId), followed.map { it.userId }.toSet(), "普通关注与特关都应返回")
        assertEquals(setOf("fb", "fc"), followed.map { it.userName }.toSet())
    }

    @Test
    fun follows_emptyWhenNoFollows() {
        val a = addUser("fa-empty", "fa-empty@example.com")

        assertTrue(repository.follows(a.userId, PageRequest.of(0, 10)).content.isEmpty())
        assertTrue(repository.follows(a.userId, PageRequest.of(0, 10)).totalElements == 0L)
        assertTrue(repository.follows(999L, PageRequest.of(0, 10)).content.isEmpty())
    }

    @Test
    fun follows_excludesDeletedFollowedUser() {
        // IN 子查询语义：关注列表中已被删除的用户不再返回
        val a = addUser("fa-del", "fa-del@example.com")
        val b = addUser("fb-del", "fb-del@example.com")
        val c = addUser("fc-del", "fc-del@example.com")
        repository.follow(a.userId, b.userId)
        repository.follow(a.userId, c.userId)
        repository.deleteById(b.userId)

        val followed = repository.follows(a.userId, PageRequest.of(0, 10)).content

        assertEquals(listOf(c.userId), followed.map { it.userId })
    }

    @Test
    fun fans_returnsFollowers() {
        val a = addUser("fan-a", "fan-a@example.com")
        val b = addUser("fan-b", "fan-b@example.com")
        val c = addUser("fan-c", "fan-c@example.com")
        repository.follow(a.userId, b.userId)
        repository.follow(c.userId, b.userId)

        val fans = repository.fans(b.userId, PageRequest.of(0, 10)).content

        assertEquals(setOf(a.userId, c.userId), fans.map { it.userId }.toSet())
        assertTrue(repository.fans(a.userId, PageRequest.of(0, 10)).content.isEmpty(), "无人关注 a")
        assertTrue(repository.fans(999L, PageRequest.of(0, 10)).content.isEmpty())
    }

    @Test
    fun follows_paginateMatchesUserFollowServiceUsage() {
        // UserFollowService:36,58 在 follows/fans 上按 pageable 分页（COUNT + LIMIT/OFFSET）
        val a = addUser("fp", "fp@example.com")
        val b = addUser("fp-b", "fp-b@example.com")
        val c = addUser("fp-c", "fp-c@example.com")
        repository.follow(a.userId, b.userId)
        repository.follow(a.userId, c.userId)

        val page = repository.follows(a.userId, PageRequest.of(0, 1))

        assertEquals(2L, page.totalElements)
        assertEquals(1, page.content.size)
        // 无 ORDER BY，页内元素应为 b / c 之一（size=1 的页不可能同时含两者）
        assertTrue(
            page.content.single().userId in setOf(b.userId, c.userId),
            "页内元素应为 b 或 c 之一，实际=${page.content.map { it.userId }}",
        )
        assertEquals(1, repository.follows(a.userId, PageRequest.of(1, 1)).content.size)
        assertTrue(repository.follows(a.userId, PageRequest.of(5, 1)).content.isEmpty())
    }

    // ------------------------------------------------------------------
    // user_follow 复合主键查询与映射（getFollowUpdatedAtMap / findFollow / setSpecialFollow / ...）
    // ------------------------------------------------------------------

    @Test
    fun getFollowUpdatedAtMap_hitsPartialAndEmptyGuard() {
        val t2 = LocalDateTime.of(2025, 5, 1, 10, 0, 0, 123_000_000)
        val t3 = LocalDateTime.of(2025, 5, 2, 11, 0, 0, 456_000_000)
        addFollow(1L, 2L, updatedAt = t2)
        addFollow(1L, 3L, updatedAt = t3)
        addFollow(1L, 4L)

        assertEquals(mapOf(2L to t2, 3L to t3), repository.getFollowUpdatedAtMap(1L, listOf(2L, 3L, 9L)))
        assertTrue(repository.getFollowUpdatedAtMap(1L, emptyList()).isEmpty())
        assertTrue(repository.getFollowUpdatedAtMap(1L, listOf(9L, 10L)).isEmpty())
    }

    @Test
    fun findFollow_existsAndNull() {
        val t = LocalDateTime.of(2025, 6, 1, 12, 0, 0)
        addFollow(1L, 2L, specialFollow = true, updatedAt = t)

        val row = repository.findFollow(1L, 2L)
        assertNotNull(row)
        assertEquals(1L, row!!.userId)
        assertEquals(2L, row.followUserId)
        assertTrue(row.specialFollow)
        assertEquals(t, row.updatedAt)
        assertNull(repository.findFollow(1L, 9L), "不存在的关注对返回 null")
        assertNull(repository.findFollow(2L, 1L), "反向组合 (2,1) 不存在")
    }

    @Test
    fun setSpecialFollow_toggleAndBaselines() {
        addFollow(1L, 2L, specialFollow = false)

        assertTrue(repository.setSpecialFollow(1L, 2L, true))
        assertTrue(repository.findFollow(1L, 2L)!!.specialFollow)
        assertTrue(repository.setSpecialFollow(1L, 2L, false))
        assertFalse(repository.findFollow(1L, 2L)!!.specialFollow)
        // 基线：相同值重复设置仍返回 true（PG UPDATE 按 WHERE 匹配行计数，不比较新旧值）
        assertTrue(repository.setSpecialFollow(1L, 2L, false))
        // 未关注时返回 false 且不插行（服务层 UserFollowService:80-92 依赖此布尔）
        assertFalse(repository.setSpecialFollow(1L, 9L, true))
        assertEquals(0, countFollow(1L, 9L))
        assertEquals(1, totalFollowRows())
    }

    @Test
    fun getSpecialFollowedTargetIds_onlySpecialFollowsInTargets() {
        addFollow(1L, 2L, specialFollow = true)
        addFollow(1L, 3L, specialFollow = false)

        assertEquals(setOf(2L), repository.getSpecialFollowedTargetIds(1L, listOf(2L, 3L, 9L)))
        assertEquals(setOf(2L), repository.getSpecialFollowedTargetIds(1L, listOf(2L)))
        assertTrue(repository.getSpecialFollowedTargetIds(1L, emptyList()).isEmpty())
        assertTrue(repository.getSpecialFollowedTargetIds(9L, listOf(2L)).isEmpty())
    }

    @Test
    fun getSpecialFollowerIds_returnsAllSpecialFollowers() {
        addFollow(1L, 5L, specialFollow = true)
        addFollow(2L, 5L, specialFollow = false)
        addFollow(3L, 5L, specialFollow = true)

        assertEquals(setOf(1L, 3L), repository.getSpecialFollowerIds(5L).toSet())
        assertTrue(repository.getSpecialFollowerIds(9L).isEmpty(), "无特关粉丝返回空列表")
    }

    @Test
    fun getFansUpdatedAtMap_partialHitAndEmptyGuard() {
        val t1 = LocalDateTime.of(2025, 7, 1, 8, 0, 0)
        val t3 = LocalDateTime.of(2025, 7, 2, 9, 0, 0)
        addFollow(1L, 2L, updatedAt = t1)
        addFollow(3L, 2L, updatedAt = t3)

        assertEquals(mapOf(1L to t1), repository.getFansUpdatedAtMap(listOf(1L, 9L), 2L))
        assertTrue(repository.getFansUpdatedAtMap(emptyList(), 2L).isEmpty())
        assertTrue(repository.getFansUpdatedAtMap(listOf(9L), 2L).isEmpty())
    }

    @Test
    fun getFollowedTargetIds_subsetAndEmptyGuard() {
        addFollow(1L, 2L)
        addFollow(1L, 3L)

        assertEquals(setOf(2L), repository.getFollowedTargetIds(1L, listOf(2L, 9L)))
        assertEquals(setOf(2L, 3L), repository.getFollowedTargetIds(1L, listOf(2L, 3L)))
        assertTrue(repository.getFollowedTargetIds(1L, emptyList()).isEmpty())
        assertTrue(repository.getFollowedTargetIds(9L, listOf(2L)).isEmpty())
    }

    @Test
    fun getFollowerTargetIds_subsetAndEmptyGuard() {
        addFollow(1L, 2L)
        addFollow(3L, 2L)

        assertEquals(setOf(1L), repository.getFollowerTargetIds(listOf(1L, 9L), 2L))
        assertEquals(setOf(1L, 3L), repository.getFollowerTargetIds(listOf(1L, 3L), 2L))
        assertTrue(repository.getFollowerTargetIds(emptyList(), 2L).isEmpty())
        assertTrue(repository.getFollowerTargetIds(listOf(1L), 9L).isEmpty())
    }

    @Test
    fun isFollowing_andIsSpecialFollowing() {
        addFollow(1L, 2L, specialFollow = false)
        addFollow(1L, 3L, specialFollow = true)

        assertTrue(repository.isFollowing(1L, 2L))
        assertTrue(repository.isFollowing(1L, 3L))
        assertFalse(repository.isFollowing(1L, 9L))
        assertFalse(repository.isFollowing(2L, 1L), "反向组合不命中")
        assertFalse(repository.isSpecialFollowing(1L, 2L), "普通关注不是特关")
        assertTrue(repository.isSpecialFollowing(1L, 3L))
        assertFalse(repository.isSpecialFollowing(1L, 9L))
    }

    // ------------------------------------------------------------------
    // 原生查询点：UserService（ANALYSIS §3 序号 6/7/8）
    // ------------------------------------------------------------------

    @Test
    fun findByUserIdOrDefault_returnsEntityOrUnknown() {
        val a = addUser("fbyid", "fbyid@example.com")

        val found = userService.findByUserIdOrDefault(a.userId)
        assertEquals(a.userId, found.userId)
        assertEquals("fbyid", found.userName)
        assertEquals("fbyid@example.com", found.email)

        val unknown = userService.findByUserIdOrDefault(999L)
        assertEquals(UserEntity.UNKNOWN, unknown, "未命中回退 UserEntity.UNKNOWN 单例")
    }

    @Test
    fun get_returnsMaaUserInfoOrNull() {
        addUser("pub", "pub@example.com", status = 1, followingCount = 2, fansCount = 3)
        addUser("inactive", "inactive@example.com", status = 0)

        val info = userService.get(1L)!!
        assertEquals("1", info.id)
        assertEquals("pub", info.userName)
        assertTrue(info.activated, "status==1 视为已激活")
        assertEquals(2, info.followingCount)
        assertEquals(3, info.fansCount)

        assertFalse(userService.get(2L)!!.activated)
        assertNull(userService.get(999L))
        assertNull(userService.get(0L))
    }

    @Test
    fun search_byUserNameHitSortAndPagination() {
        val alice = addUser("alice", "alice@example.com", fansCount = 5)
        val alicia = addUser("alicia", "alicia@example.com", fansCount = 10)
        addUser("bob", "bob@example.com", fansCount = 3)
        // 含 LIKE 元字符的用户名，用于验证转义后按字面匹配
        val under = addUser("a_b", "a_b@example.com", fansCount = 7)
        val percent = addUser("c%d", "c%d@example.com", fansCount = 8)

        // 命中 + 粉丝数降序
        assertEquals(
            listOf(alicia.userId, alice.userId),
            userService.search("ali", 0, 10).map { it.userId },
        )
        // 分页边界
        assertEquals(listOf(alicia.userId), userService.search("ali", 0, 1).map { it.userId })
        assertEquals(listOf(alice.userId), userService.search("ali", 1, 1).map { it.userId })
        assertTrue(userService.search("ali", 10, 1).isEmpty(), "offset 越界返回空")
        // 未命中
        assertTrue(userService.search("zzz", 0, 10).isEmpty())
        // 空关键词 `%%` 匹配全表
        assertEquals(5, userService.search("", 0, 10).size)
        // 修复：`_` 转义后按字面匹配，仅命中含下划线的用户名
        assertEquals(listOf(under.userId), userService.search("_", 0, 10).map { it.userId })
        // 修复：`%` 转义后按字面匹配，仅命中含百分号的用户名
        assertEquals(listOf(percent.userId), userService.search("%", 0, 10).map { it.userId })
        // 含元字符的子串同样按字面匹配
        assertEquals(listOf(under.userId), userService.search("a_", 0, 10).map { it.userId })
        assertEquals(listOf(percent.userId), userService.search("c%", 0, 10).map { it.userId })
    }

    @Test
    fun findByUserIdOrDefaultInCache_cachesAndInvalidates() {
        // 清理缓存，避免静态缓存跨用例污染
        InternalComposeCache.invalidateMaaUserById("1")
        val user = addUser("cached", "cached@example.com")

        // 首次调用回源查库并写入缓存
        val first = userService.findByUserIdOrDefaultInCache(user.userId)
        assertEquals(user.userId, first.userId)
        assertNotNull(InternalComposeCache.getMaaUserCache(user.userId.toString()), "首次调用后应命中缓存")

        // 删库后缓存仍返回旧实体（基线：缓存回源不查库，且缓存的是可变 UserEntity）
        repository.deleteById(user.userId)
        val second = userService.findByUserIdOrDefaultInCache(user.userId)
        assertEquals(user.userId, second.userId)

        // 失效后回源查库 → UNKNOWN（UNKNOWN 也会被写回缓存）
        InternalComposeCache.invalidateMaaUserById(user.userId.toString())
        val third = userService.findByUserIdOrDefaultInCache(user.userId)
        assertEquals(0L, third.userId)
        assertNotNull(InternalComposeCache.getMaaUserCache(user.userId.toString()))

        // 清理
        InternalComposeCache.invalidateMaaUserById(user.userId.toString())
    }
}
