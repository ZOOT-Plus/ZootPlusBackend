package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.service.model.RatingCount
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

/**
 * [RatingRepository] 集成测试。
 *
 * 覆盖范围：本模块全部 public 方法 —— findByTypeAndKeyAndUserId / getRatingCountAfter /
 * getAllRatingCount / countByTypeKeyInRatingAfter / findById / deleteById / existsById /
 * insertEntity / updateEntity，以及继承的 findAll / save / count。
 *
 * 基线记录：
 * - 唯一索引 idx_rating_unique 冲突 → PostgreSQL SQLState 23505（经 Jdbi 包装，异常链中仍含 PSQLException）。
 * - rating.key 列 DDL 为 NOT NULL，不存在 key=NULL 的分组场景，不测。
 */
class RatingRepositoryTest : TestDbSupport() {

    private val repository = RatingRepository(jdbi)

    // ------------------------------------------------------------------
    // findByTypeAndKeyAndUserId
    // ------------------------------------------------------------------

    @Test
    fun findByTypeAndKeyAndUserIdHit() {
        val inserted = insertRating(
            type = Rating.KeyType.COPILOT,
            key = "copilot-100",
            userId = "user-1",
            rating = RatingType.LIKE,
            rateTime = LocalDateTime.of(2024, 5, 1, 10, 30, 0),
        )

        val found = repository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-100", "user-1")

        assertNotNull(found)
        assertEquals(inserted.id, found!!.id)
        assertEquals(Rating.KeyType.COPILOT, found.type)
        assertEquals("copilot-100", found.key)
        assertEquals("user-1", found.userId)
        assertEquals(RatingType.LIKE, found.rating)
        assertEquals(LocalDateTime.of(2024, 5, 1, 10, 30, 0), found.rateTime)
    }

    @Test
    fun findByTypeAndKeyAndUserIdMissesWhenAnyDimensionDiffers() {
        insertRating(key = "copilot-100", userId = "user-1")

        assertNull(repository.findByTypeAndKeyAndUserId(Rating.KeyType.COMMENT, "copilot-100", "user-1"))
        assertNull(repository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-101", "user-1"))
        assertNull(repository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-100", "user-2"))
        assertNull(repository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-101", "user-2"))
    }

    @Test
    fun findByTypeAndKeyAndUserIdAllowsSameKeyAndUserUnderDifferentType() {
        insertRating(type = Rating.KeyType.COPILOT, key = "copilot-100", userId = "user-1", rating = RatingType.LIKE)
        insertRating(type = Rating.KeyType.COMMENT, key = "copilot-100", userId = "user-1", rating = RatingType.DISLIKE)

        val copilot = repository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-100", "user-1")
        val comment = repository.findByTypeAndKeyAndUserId(Rating.KeyType.COMMENT, "copilot-100", "user-1")

        assertNotNull(copilot)
        assertNotNull(comment)
        assertEquals(RatingType.LIKE, copilot!!.rating)
        assertEquals(RatingType.DISLIKE, comment!!.rating)
        assertEquals(2L, repository.count(), "(key, user_id) 相同但 type 不同应各自成行")
    }

    // ------------------------------------------------------------------
    // insertEntity
    // ------------------------------------------------------------------

    @Test
    fun insertEntityBackfillsIdAndStoresEnumAsName() {
        val like = insertRating(Rating.KeyType.COPILOT, "copilot-100", "user-1", RatingType.LIKE)
        val none = insertRating(Rating.KeyType.COMMENT, "comment-1", "user-1", RatingType.NONE)

        assertTrue(like.id > 0, "自增主键应回填")
        assertTrue(none.id > like.id, "自增主键应递增")

        assertEquals("COPILOT" to "LIKE", selectEnumNames(like.id))
        assertEquals("COMMENT" to "NONE", selectEnumNames(none.id))
    }

    @Test
    fun insertEntityDuplicateUniqueTripleThrowsUniqueViolation() {
        insertRating(Rating.KeyType.COPILOT, "copilot-100", "user-1", RatingType.LIKE)

        // 无 ON CONFLICT：重复 (type, key, user_id) 命中唯一索引 idx_rating_unique
        assertUniqueConstraintViolation {
            insertRating(Rating.KeyType.COPILOT, "copilot-100", "user-1", RatingType.DISLIKE)
        }

        assertEquals(1L, repository.count(), "唯一约束冲突后不应新增行")
        assertEquals(RatingType.LIKE, repository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-100", "user-1")!!.rating)
    }

    // ------------------------------------------------------------------
    // insertOrGet（竞态安全的“插入或获取”）
    // ------------------------------------------------------------------

    @Test
    fun insertOrGetInsertsAndReturnsRowWithIdWhenAbsent() {
        val entity = RatingEntity(
            type = Rating.KeyType.COPILOT,
            key = "copilot-100",
            userId = "user-1",
            rating = RatingType.NONE,
            rateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0),
        )

        val result = repository.insertOrGet(entity)

        assertTrue(result.id > 0, "未命中时应新建并回填自增主键")
        assertEquals(RatingType.NONE, result.rating)
        assertEquals(1L, repository.count())
        // 快照应已附着（与库一致），后续 updateEntity 走脏检查
        assertEquals(emptyList<String>(), result.dirtyColumns())
    }

    @Test
    fun insertOrGetReturnsExistingRowWhenConflictAlreadyExists() {
        // 模拟并发竞态的“后到者”：另一请求已抢先插入同一 (type, key, user_id)
        val winner = insertRating(
            Rating.KeyType.COPILOT,
            "copilot-100",
            "user-1",
            RatingType.NONE,
            LocalDateTime.of(2024, 1, 1, 0, 0, 0),
        )

        val loser = RatingEntity(
            type = Rating.KeyType.COPILOT,
            key = "copilot-100",
            userId = "user-1",
            rating = RatingType.NONE,
            rateTime = LocalDateTime.of(2024, 1, 2, 0, 0, 0),
        )

        val result = repository.insertOrGet(loser)

        assertEquals(winner.id, result.id, "冲突时应返回已存在行，而非新建且不抛唯一约束异常")
        assertEquals(1L, repository.count(), "冲突时不应新增行")
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0, 0), result.rateTime, "应返回已存在行的值")
    }

    @Test
    fun insertOrGetDistinguishesByTypeForKeyAndUser() {
        insertRating(Rating.KeyType.COPILOT, "copilot-100", "user-1", RatingType.LIKE)

        val other = RatingEntity(
            type = Rating.KeyType.COMMENT,
            key = "copilot-100",
            userId = "user-1",
            rating = RatingType.NONE,
            rateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0),
        )

        val result = repository.insertOrGet(other)

        assertNotEquals(repository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-100", "user-1")!!.id, result.id)
        assertEquals(2L, repository.count(), "type 不同应视为不同行各自插入")
    }

    // ------------------------------------------------------------------
    // getRatingCountAfter
    // ------------------------------------------------------------------

    @Test
    fun getRatingCountAfterStrictlyGreaterAndGroupsByKey() {
        val t1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
        val t2 = LocalDateTime.of(2024, 1, 2, 0, 0, 0)
        val t3 = LocalDateTime.of(2024, 1, 3, 0, 0, 0)
        insertRating(key = "copilot-1", userId = "u1", rateTime = t1)
        insertRating(key = "copilot-1", userId = "u2", rateTime = t2)
        insertRating(key = "copilot-1", userId = "u3", rating = RatingType.DISLIKE, rateTime = t3)
        // COMMENT 类型同样参与统计（方法不过滤 type）
        insertRating(type = Rating.KeyType.COMMENT, key = "copilot-2", userId = "u4", rateTime = t3)

        val after = repository.getRatingCountAfter(t2)

        // 边界相等（rate_time == t2）不统计，严格大于才计入；按 key 分组聚合
        assertEquals(mapOf("copilot-1" to 1L, "copilot-2" to 1L), after.asCountMap())
    }

    @Test
    fun getRatingCountAfterBeforeAllRowsCountsEverything() {
        val t1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
        insertRating(key = "copilot-1", userId = "u1", rateTime = t1)
        insertRating(key = "copilot-1", userId = "u2", rateTime = t1.plusDays(1))
        insertRating(key = "copilot-2", userId = "u3", rateTime = t1.plusDays(2))

        val all = repository.getRatingCountAfter(t1.minusDays(1))

        assertEquals(mapOf("copilot-1" to 2L, "copilot-2" to 1L), all.asCountMap())
    }

    @Test
    fun getRatingCountAfterNoDataAfterBoundaryReturnsEmpty() {
        insertRating(key = "copilot-1", userId = "u1", rateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0))

        assertTrue(repository.getRatingCountAfter(LocalDateTime.of(2024, 12, 31, 23, 59, 59)).isEmpty())
    }

    @Test
    fun getRatingCountAfterEmptyTableReturnsEmpty() {
        assertTrue(repository.getRatingCountAfter(LocalDateTime.of(2020, 1, 1, 0, 0, 0)).isEmpty())
    }

    // ------------------------------------------------------------------
    // getAllRatingCount
    // ------------------------------------------------------------------

    @Test
    fun getAllRatingCountGroupsAllRows() {
        val t1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
        insertRating(key = "copilot-1", userId = "u1", rateTime = t1)
        insertRating(key = "copilot-1", userId = "u2", rateTime = t1.plusDays(1))
        insertRating(key = "copilot-1", userId = "u3", rateTime = t1.plusDays(2))
        insertRating(type = Rating.KeyType.COMMENT, key = "copilot-2", userId = "u4", rateTime = t1.plusDays(3))

        val counts = repository.getAllRatingCount()

        assertEquals(mapOf("copilot-1" to 3L, "copilot-2" to 1L), counts.asCountMap())
    }

    @Test
    fun getAllRatingCountEmptyTableReturnsEmpty() {
        assertTrue(repository.getAllRatingCount().isEmpty())
    }

    // ------------------------------------------------------------------
    // updateEntity
    // ------------------------------------------------------------------

    @Test
    fun updateEntityPersistsChangedRatingAndRateTime() {
        val inserted = insertRating(
            Rating.KeyType.COPILOT,
            "copilot-100",
            "user-1",
            RatingType.LIKE,
            LocalDateTime.of(2024, 1, 1, 12, 0, 0),
        )
        val loaded = repository.findById(inserted.id)!!

        val newTime = LocalDateTime.of(2024, 6, 1, 8, 0, 0)
        loaded.rating = RatingType.DISLIKE
        loaded.rateTime = newTime
        repository.updateEntity(loaded)

        val reloaded = repository.findById(inserted.id)!!
        assertEquals(RatingType.DISLIKE, reloaded.rating)
        assertEquals(newTime, reloaded.rateTime)
        assertEquals(Rating.KeyType.COPILOT, reloaded.type)
        assertEquals("copilot-100", reloaded.key)
        assertEquals("user-1", reloaded.userId)
    }

    @Test
    fun updateEntityFlushChangesOnlyUpdatesDirtyColumn() {
        val inserted = insertRating(
            Rating.KeyType.COPILOT,
            "copilot-100",
            "user-1",
            RatingType.LIKE,
            LocalDateTime.of(2024, 1, 1, 12, 0, 0),
        )
        val loaded = repository.findById(inserted.id)!!

        // 模拟并发修改：绕过 repository 直接改库中 rate_time
        val externalTime = LocalDateTime.of(2030, 1, 1, 0, 0, 0)
        updateRateTimeDirectly(inserted.id, externalTime)

        // 仅修改 rating 后 updateEntity：脏检查应只更新 rating 一列
        loaded.rating = RatingType.NONE
        repository.updateEntity(loaded)

        val row = repository.findById(inserted.id)!!
        assertEquals(RatingType.NONE, row.rating)
        assertEquals(externalTime, row.rateTime, "updateEntity 不应覆盖未修改的 rate_time 列")
    }

    @Test
    fun updateEntityWithoutChangesIsNoOp() {
        val inserted = insertRating(
            Rating.KeyType.COPILOT,
            "copilot-100",
            "user-1",
            RatingType.LIKE,
            LocalDateTime.of(2024, 1, 1, 12, 0, 0),
        )
        val loaded = repository.findById(inserted.id)!!

        repository.updateEntity(loaded)

        val reloaded = repository.findById(inserted.id)!!
        assertEquals(RatingType.LIKE, reloaded.rating)
        assertEquals(LocalDateTime.of(2024, 1, 1, 12, 0, 0), reloaded.rateTime)
    }

    // ------------------------------------------------------------------
    // findById / existsById / deleteById
    // ------------------------------------------------------------------

    @Test
    fun findByIdHitAndMiss() {
        val inserted = insertRating(key = "copilot-100", userId = "user-1")

        val found = repository.findById(inserted.id)

        assertNotNull(found)
        assertEquals("copilot-100", found!!.key)
        assertEquals(RatingType.LIKE, found.rating)
        assertNull(repository.findById(999_999L))
    }

    @Test
    fun existsByIdTrueAndFalse() {
        val inserted = insertRating(key = "copilot-100", userId = "user-1")

        assertTrue(repository.existsById(inserted.id))
        assertFalse(repository.existsById(999_999L))
    }

    @Test
    fun deleteByIdRemovesRowAndReportsAffectedRows() {
        val inserted = insertRating(key = "copilot-100", userId = "user-1")

        assertTrue(repository.deleteById(inserted.id), "删除存在的行应返回 true")
        assertFalse(repository.existsById(inserted.id))
        assertEquals(0L, repository.count())

        assertFalse(repository.deleteById(inserted.id), "已删除的行再次删除应返回 false")
        assertFalse(repository.deleteById(999_999L))
    }

    // ------------------------------------------------------------------
    // 原继承的公共 API：save / findAll / count
    // ------------------------------------------------------------------

    @Test
    fun saveNewEntityInsertsWithIdBackfill() {
        val entity = RatingEntity(
            type = Rating.KeyType.COPILOT,
            key = "copilot-100",
            userId = "user-1",
            rating = RatingType.LIKE,
            rateTime = LocalDateTime.of(2024, 1, 1, 12, 0, 0),
        )

        val saved = repository.save(entity)

        assertTrue(saved.id > 0, "save 新实体（id=0）应插入并回填自增主键")
        assertEquals(1L, repository.count())
        assertEquals("copilot-100", repository.findById(saved.id)!!.key)
    }

    @Test
    fun saveExistingEntityUpdates() {
        val inserted = insertRating(key = "copilot-100", userId = "user-1", rating = RatingType.LIKE)
        val loaded = repository.findById(inserted.id)!!

        loaded.rating = RatingType.DISLIKE
        repository.save(loaded)

        assertEquals(RatingType.DISLIKE, repository.findById(inserted.id)!!.rating)
        assertEquals(1L, repository.count(), "save 已存在实体应 update 而非新增")
    }

    @Test
    fun saveEntityWithNonExistentIdInserts() {
        val entity = RatingEntity(
            id = 999_999L,
            type = Rating.KeyType.COPILOT,
            key = "copilot-100",
            userId = "user-1",
            rating = RatingType.LIKE,
            rateTime = LocalDateTime.of(2024, 1, 1, 12, 0, 0),
        )

        repository.save(entity)

        assertEquals(1L, repository.count())
        assertEquals(999_999L, repository.findById(999_999L)!!.id)
    }

    @Test
    fun findAllAndCount() {
        assertEquals(0L, repository.count())
        assertTrue(repository.findAll().isEmpty())

        val a = insertRating(key = "copilot-1", userId = "u1")
        val b = insertRating(key = "copilot-2", userId = "u2")

        assertEquals(2L, repository.count())
        // findAll 无排序保证，按 id 集合断言
        assertEquals(setOf(a.id, b.id), repository.findAll().map { it.id }.toSet())
    }

    // ------------------------------------------------------------------
    // 原生查询点迁移：CopilotScoreRefreshTask.counts（ANALYSIS §3 #12）
    // ------------------------------------------------------------------

    @Test
    fun countByTypeKeyInRatingAfterMatchesManualAggregation() {
        val start = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
        insertRating(key = "copilot-1", userId = "u1", rating = RatingType.LIKE, rateTime = start)
        insertRating(key = "copilot-1", userId = "u2", rating = RatingType.LIKE, rateTime = start.plusDays(1))
        insertRating(key = "copilot-2", userId = "u3", rating = RatingType.LIKE, rateTime = start.plusDays(2))
        // 类型不符：不应计入
        insertRating(
            type = Rating.KeyType.COMMENT,
            key = "copilot-3",
            userId = "u4",
            rating = RatingType.LIKE,
            rateTime = start.plusDays(3),
        )
        // 评级不符：不应计入
        insertRating(key = "copilot-4", userId = "u5", rating = RatingType.DISLIKE, rateTime = start.plusDays(4))
        // 时间不符（rate_time < startTime）：不应计入；边界等于 startTime 的应计入
        insertRating(key = "copilot-5", userId = "u6", rating = RatingType.LIKE, rateTime = start.minusDays(1))

        val actual = repository.countByTypeKeyInRatingAfter(
            type = Rating.KeyType.COPILOT,
            keys = listOf("copilot-1", "copilot-2", "copilot-3", "copilot-4", "copilot-5", "copilot-missing"),
            rating = RatingType.LIKE,
            startTime = start,
        )

        assertEquals(mapOf("copilot-1" to 2L, "copilot-2" to 1L), actual.asCountMap())
    }

    @Test
    fun countByTypeKeyInRatingAfterEmptyKeysReturnsEmpty() {
        insertRating(key = "copilot-1", userId = "u1", rating = RatingType.LIKE, rateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0))

        val actual = repository.countByTypeKeyInRatingAfter(
            type = Rating.KeyType.COPILOT,
            keys = emptyList(),
            rating = RatingType.LIKE,
            startTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0),
        )

        assertTrue(actual.isEmpty(), "空 keys 守卫应返回空列表")
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun insertRating(
        type: Rating.KeyType = Rating.KeyType.COPILOT,
        key: String,
        userId: String,
        rating: RatingType = RatingType.LIKE,
        rateTime: LocalDateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0),
    ): RatingEntity {
        val entity = RatingEntity(
            type = type,
            key = key,
            userId = userId,
            rating = rating,
            rateTime = rateTime,
        )
        return repository.insertEntity(entity)
    }

    private fun List<RatingCount>.asCountMap(): Map<String, Long> = associate { it.key to it.count }

    /** 绕过 repository 直接修改库中 rate_time，用于验证 updateEntity 的单列更新语义。 */
    private fun updateRateTimeDirectly(id: Long, rateTime: LocalDateTime) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE rating SET rate_time = ? WHERE id = ?").use { ps ->
                ps.setObject(1, rateTime)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
    }

    /** 以原生 SQL 读回枚举列落库名。 */
    private fun selectEnumNames(id: Long): Pair<String, String> = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT type, rating FROM rating WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "id=$id 行应存在" }
                rs.getString(1) to rs.getString(2)
            }
        }
    }

    private fun findPsqlException(t: Throwable): PSQLException? =
        generateSequence(t) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()

    private fun assertUniqueConstraintViolation(block: () -> Unit) {
        val ex = assertThrows(Exception::class.java) { block() }
        val psql = findPsqlException(ex)
        assertNotNull(psql, "异常链中应包含 PSQLException，实际=${ex::class.simpleName}: ${ex.message}")
        assertEquals("23505", psql!!.sqlState)
        assertTrue(psql.message.orEmpty().contains("idx_rating_unique"))
    }
}
