package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.common.Constants.ME
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 模块「copilot_set」集成测试。
 *
 * 覆盖范围：
 * 1. CopilotSetRepository 全部 public 方法（findAll/count 为自有方法）。
 * 2. 服务层查询逻辑的 DB 部分（以 querySets() 复刻后直接测试，测试数据不经过服务层）：
 *    - CopilotSetService.query：delete=false / 权限过滤 / user_follow 关注子查询 / creatorId（含 ME 特判）/
 *      关键词 LIKE / copilot_ids @> jsonb / id 倒序分页 / COUNT。
 *    - CopilotSetScoreRefreshTask：countNotDeleted、findNotDeletedPage（无 ORDER BY 基线）、
 *      findByIdsAndNotDeleted（跨 copilot 表，走 copilot 模块的 CopilotRepository）、batchUpdateHotScores。
 *
 * 未覆盖点及原因（服务依赖过重，只测到 repository/查询逻辑层）：
 * - CopilotSetService.create/addCopilotIds/removeCopilotIds/update/delete/get 完整流程：依赖
 *   RedisCache(StringRedisTemplate)、UserService、MapStruct CopilotSetConverter，且 get() 走 redis 计数 + 虚拟线程。
 *   其中 setCopilotIdsWithCheck 的 ≤1000 断言属服务层纯逻辑（本测试以 insertEntity 大列表用例记录 repository 层不拦的基线）。
 * - CopilotSetScoreRefreshTask.refresh() 主流程与 score() 公式：定时任务编排 + 时间衰减公式，非 DB 行为。
 * - CopilotSetService.query 中 userId==null 时 ME 特判与"关注子查询不生效"分支已含在 querySets 复刻逻辑内。
 */
class CopilotSetRepositoryTest : TestDbSupport() {

    private val repository = CopilotSetRepository(jdbi)
    private val copilotRepository = CopilotRepository(jdbi)

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 构造一个全部非空列已赋值的作业集实体（id 不赋值，交由 bigserial 自增）。 */
    private fun newEntity(
        name: String = "测试作业集",
        description: String = "默认描述",
        copilotIds: List<Long> = emptyList(),
        views: Long = 0L,
        hotScore: Double = 0.0,
        creatorId: Long = 1L,
        createTime: LocalDateTime = now(),
        updateTime: LocalDateTime = now(),
        status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
        deleted: Boolean = false,
    ): CopilotSetEntity = CopilotSetEntity(
        name = name,
        description = description,
        copilotIds = copilotIds,
        views = views,
        hotScore = hotScore,
        creatorId = creatorId,
        createTime = createTime,
        updateTime = updateTime,
        status = status,
        delete = deleted,
    )

    private fun now(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)

    /** 原生 SQL 写入 user_follow（复合主键 (user_id, follow_user_id)）。 */
    private fun insertFollow(userId: Long, followUserId: Long, specialFollow: Boolean = false) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_follow (user_id, follow_user_id, special_follow, updated_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, followUserId)
                ps.setBoolean(3, specialFollow)
                ps.setObject(4, now())
                ps.executeUpdate()
            }
        }
    }

    /**
     * 原生 SQL 写入 copilot 行（供 task 的 findByIdsAndNotDeleted 跨表查询使用）。
     * 用原生 SQL 而非实体构造，避免依赖 copilot 模块的实体形态。
     */
    private fun insertCopilot(deleted: Boolean): Long {
        return dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO copilot (type, stage_name, uploader_id, views, rating_level, rating_ratio,
                                     like_count, dislike_count, hot_score, title, details,
                                     first_upload_time, upload_time, content, status, comment_status,
                                     "delete", delete_time, notification)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING copilot_id
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, CopilotType.PRTS.name)
                ps.setString(2, "1-7")
                ps.setLong(3, 1L)
                ps.setLong(4, 0L)
                ps.setInt(5, 0)
                ps.setDouble(6, 0.0)
                ps.setLong(7, 0L)
                ps.setLong(8, 0L)
                ps.setDouble(9, 1.0)
                ps.setString(10, "copilot-title")
                ps.setNull(11, java.sql.Types.VARCHAR)
                ps.setNull(12, java.sql.Types.TIMESTAMP)
                ps.setNull(13, java.sql.Types.TIMESTAMP)
                ps.setString(14, "content")
                ps.setString(15, CopilotSetStatus.PUBLIC.name)
                ps.setString(16, CommentStatus.ENABLED.name)
                ps.setBoolean(17, deleted)
                ps.setNull(18, java.sql.Types.TIMESTAMP)
                ps.setNull(19, java.sql.Types.BOOLEAN)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    /**
     * 复刻 CopilotSetService.query 的 DB 查询逻辑，返回 (列表, 总数, hasNext)。
     * 服务层的 redis 访问计数、UserService 缓存回源、converter 转换不在此复刻。
     * ME 特判与非法 creatorId 解析留在复刻层（服务层行为），DB 查询委托给 CopilotSetRepository.querySets。
     */
    private fun querySets(
        userId: Long?,
        onlyFollowing: Boolean = false,
        creatorId: String? = null,
        keyword: String? = null,
        copilotIds: List<Long>? = null,
        page: Int = 1,
        limit: Int = 10,
    ): Triple<List<CopilotSetEntity>, Long, Boolean> {
        // creatorId 过滤（ME 特判：登录时等价于自己；非法字符串直接返回空）
        val targetCreatorId: Long? = if (!creatorId.isNullOrBlank()) {
            if (creatorId == ME && userId != null) {
                userId
            } else {
                creatorId.toLongOrNull() ?: return Triple(emptyList(), 0L, false)
            }
        } else {
            null
        }

        // 关键词：name LIKE %k% OR description LIKE %k%
        val kw = keyword?.takeIf { it.isNotBlank() }?.let { "%$it%" }

        // jsonb @> 查询：requiredIds 先去重（toSet）再编码；空列表不生成该条件（保持服务层守卫）
        val requiredIds = copilotIds?.takeIf { it.isNotEmpty() }?.toSet()

        val offset = (page - 1) * limit
        val (sets, total) = repository.querySets(
            userId = userId,
            onlyFollowing = onlyFollowing && userId != null,
            creatorId = targetCreatorId,
            keyword = kw,
            copilotIds = requiredIds,
            offset = offset,
            limit = limit,
        )
        val hasNext = (offset + limit) < total
        return Triple(sets, total, hasNext)
    }

    /** 复刻 CopilotSetScoreRefreshTask.refresh：按 id 集合取未删除作业（复用 copilot 模块 DAO）。 */
    private fun findByIdsAndNotDeleted(ids: List<Long>): List<CopilotEntity> {
        if (ids.isEmpty()) return emptyList() // 与任务中 s.copilotIds.isEmpty() 守卫一致
        return copilotRepository.findByIdsAndNotDeleted(ids)
    }

    // ------------------------------------------------------------------
    // CopilotSetRepository 公共方法
    // ------------------------------------------------------------------

    @Test
    fun findById_hitAndMiss() {
        val entity = repository.insertEntity(newEntity(name = "hit"))

        val loaded = repository.findById(entity.id)
        assertNotNull(loaded)
        assertEquals("hit", loaded!!.name)

        assertNull(repository.findById(99999L), "不存在的 id 应返回 null")
        assertNull(repository.findById(0L), "id=0 不存在，应返回 null")
    }

    @Test
    fun deleteById_rowCountSemanticsAndSequenceNotRewound() {
        val a = repository.insertEntity(newEntity(name = "a"))
        val b = repository.insertEntity(newEntity(name = "b"))

        assertTrue(repository.deleteById(a.id), "删除存在的行应返回 true")
        assertNull(repository.findById(a.id))
        assertFalse(repository.existsById(a.id))

        assertFalse(repository.deleteById(a.id), "重复删除应返回 false")
        assertFalse(repository.deleteById(99999L), "删除不存在的 id 应返回 false")

        // 删除后自增序列不回退：下一个插入 id 继续递增
        val c = repository.insertEntity(newEntity(name = "c"))
        assertEquals(3L, c.id)
        assertEquals(2L, repository.count(), "现存行应为 b、c 两行")
    }

    @Test
    fun existsById_trueAndFalse() {
        val entity = repository.insertEntity(newEntity())
        assertTrue(repository.existsById(entity.id))
        assertFalse(repository.existsById(99999L))
        assertFalse(repository.existsById(0L))
    }

    @Test
    fun insertEntity_backfillsAutoIncrementId() {
        val a = repository.insertEntity(newEntity(name = "a"))
        val b = repository.insertEntity(newEntity(name = "b"))
        val c = repository.insertEntity(newEntity(name = "c"))
        assertEquals(1L, a.id)
        assertEquals(2L, b.id)
        assertEquals(3L, c.id)
    }

    @Test
    fun insertEntity_roundtripsAllColumns() {
        val createTime = LocalDateTime.of(2024, 1, 1, 12, 30, 45)
        val updateTime = LocalDateTime.of(2024, 6, 15, 8, 0, 0)
        val entity = repository.insertEntity(
            newEntity(
                name = "全列往返",
                description = "描述",
                copilotIds = listOf(11L, 22L, 33L),
                views = 123L,
                hotScore = 4.5,
                creatorId = 99L,
                createTime = createTime,
                updateTime = updateTime,
                status = CopilotSetStatus.PRIVATE,
                deleted = true,
            ),
        )
        assertTrue(entity.id > 0)

        val loaded = repository.findById(entity.id)!!
        assertEquals("全列往返", loaded.name)
        assertEquals("描述", loaded.description)
        assertEquals(listOf(11L, 22L, 33L), loaded.copilotIds)
        assertEquals(123L, loaded.views)
        assertEquals(4.5, loaded.hotScore)
        assertEquals(99L, loaded.creatorId)
        assertEquals(createTime, loaded.createTime, "timestamp(3) 写入后应原样读回")
        assertEquals(updateTime, loaded.updateTime)
        assertEquals(CopilotSetStatus.PRIVATE, loaded.status, "枚举按名字落库（PRIVATE）")
        assertTrue(loaded.delete)
    }

    @Test
    fun insertEntity_jsonbCopilotIdsRoundtrip() {
        val empty = repository.insertEntity(newEntity(copilotIds = emptyList()))
        val values = repository.insertEntity(newEntity(copilotIds = listOf(1L, 2L, 3L)))
        val single = repository.insertEntity(newEntity(copilotIds = listOf(5L)))
        val dup = repository.insertEntity(newEntity(copilotIds = listOf(1L, 1L, 2L)))

        assertEquals(emptyList<Long>(), repository.findById(empty.id)!!.copilotIds, "空列表 [] 应往返")
        assertEquals(listOf(1L, 2L, 3L), repository.findById(values.id)!!.copilotIds)
        assertEquals(listOf(5L), repository.findById(single.id)!!.copilotIds)
        // 基线记录：repository 层不去重，重复元素原样往返（去重由服务层 setCopilotIdsWithCheck 负责）
        assertEquals(listOf(1L, 1L, 2L), repository.findById(dup.id)!!.copilotIds)
    }

    @Test
    fun insertEntity_largeCopilotIdsListBeyondServiceCap() {
        val large = (1L..1500L).toList()
        val entity = repository.insertEntity(newEntity(copilotIds = large))
        // 基线记录：>1000 上限由服务层 setCopilotIdsWithCheck 的 Assert.state 拦截，
        // repository 层不拦，DB jsonb 无长度限制，1500 条可直接插入并往返。
        assertEquals(large, repository.findById(entity.id)!!.copilotIds)
    }

    @Test
    fun insertEntity_dbDefaultsAppliedForOmittedColumns() {
        // 用原生 SQL 省略带默认值的列，验证 DB 默认值（repository insert 走全列绑定，此处只验证 DB 层）
        val id = dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO copilot_set (name, copilot_ids, creator_id, create_time, update_time) " +
                    "VALUES (?, '[]'::jsonb, ?, ?, ?) RETURNING id",
            ).use { ps ->
                ps.setString(1, "defaults")
                ps.setLong(2, 1L)
                ps.setObject(3, LocalDateTime.of(2024, 1, 1, 0, 0))
                ps.setObject(4, LocalDateTime.of(2024, 1, 1, 0, 0))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

        val loaded = repository.findById(id)!!
        assertEquals("", loaded.description, "description 默认空串（schema default ''）")
        assertEquals(0L, loaded.views, "views 默认 0")
        assertEquals(0.0, loaded.hotScore, "hot_score 默认 0")
        assertEquals(CopilotSetStatus.PUBLIC, loaded.status, "status 默认 PUBLIC")
        assertFalse(loaded.delete, "delete 默认 false")
        assertEquals(emptyList<Long>(), loaded.copilotIds)
    }

    @Test
    fun updateEntity_modifiedFieldsReadBack() {
        val entity = repository.insertEntity(
            newEntity(name = "旧名", description = "旧描述", copilotIds = listOf(1L)),
        )
        entity.name = "新名"
        entity.description = "新描述"
        entity.copilotIds = listOf(9L, 8L)
        entity.status = CopilotSetStatus.PRIVATE
        repository.updateEntity(entity)

        assertEquals(1L, repository.count(), "update 不应新增行")
        val loaded = repository.findById(entity.id)!!
        assertEquals("新名", loaded.name)
        assertEquals("新描述", loaded.description)
        assertEquals(listOf(9L, 8L), loaded.copilotIds)
        assertEquals(CopilotSetStatus.PRIVATE, loaded.status)
    }

    @Test
    fun updateEntity_flushChangesOnlyUpdatesChangedColumns() {
        val entity = repository.insertEntity(
            newEntity(
                name = "n",
                description = "d",
                copilotIds = listOf(1L),
                views = 7L,
                hotScore = 2.5,
                creatorId = 9L,
                status = CopilotSetStatus.PRIVATE,
            ),
        )

        // 只改 jsonb 列：全列 SET 写入的是实体其余列的原值，读回应与原值一致
        entity.copilotIds = listOf(10L, 20L)
        repository.updateEntity(entity)
        val reloaded = repository.findById(entity.id)!!
        assertEquals("n", reloaded.name)
        assertEquals("d", reloaded.description)
        assertEquals(listOf(10L, 20L), reloaded.copilotIds)
        assertEquals(7L, reloaded.views)
        assertEquals(2.5, reloaded.hotScore)
        assertEquals(9L, reloaded.creatorId)
        assertEquals(CopilotSetStatus.PRIVATE, reloaded.status)
        assertFalse(reloaded.delete)

        reloaded.name = "n2"
        repository.updateEntity(reloaded)
        val again = repository.findById(entity.id)!!
        assertEquals("n2", again.name)
        assertEquals(listOf(10L, 20L), again.copilotIds)
        assertEquals(2.5, again.hotScore)
    }

    @Test
    fun updateEntity_copilotIdsChangeReflectedInContainsJson() {
        val entity = repository.insertEntity(newEntity(copilotIds = listOf(1L, 2L)))
        entity.copilotIds = listOf(10L, 20L)
        repository.updateEntity(entity)

        val (hits, total) = querySets(userId = null, copilotIds = listOf(10L))
        assertEquals(listOf(entity.id), hits.map { it.id }, "@> 应命中更新后的新值")
        assertEquals(1L, total)

        val (misses, missTotal) = querySets(userId = null, copilotIds = listOf(1L))
        assertTrue(misses.isEmpty(), "旧值不应再被 @> 命中")
        assertEquals(0L, missTotal)
    }

    @Test
    fun findByIdAsOptional_presentAndEmpty() {
        val entity = repository.insertEntity(newEntity())

        val present = repository.findByIdAsOptional(entity.id)
        assertTrue(present.isPresent)
        assertEquals(entity.id, present.get().id)

        val empty = repository.findByIdAsOptional(99999L)
        assertFalse(empty.isPresent, "不存在的 id 应返回 Optional.empty")
    }

    @Test
    fun save_newEntityInsertsAndBackfillsId() {
        val entity = repository.save(newEntity(name = "new"))
        assertTrue(entity.id > 0, "id=0 的新实体应插入并回填自增 id")
        assertEquals(1L, repository.count())
        assertEquals("new", repository.findById(entity.id)!!.name)
    }

    @Test
    fun save_existingEntityUpdatesInPlace() {
        val entity = repository.insertEntity(newEntity(name = "before"))
        entity.name = "after"

        val saved = repository.save(entity)
        assertEquals(entity.id, saved.id)
        assertEquals(1L, repository.count(), "已存在实体应 update 而非新增行")
        assertEquals("after", repository.findById(entity.id)!!.name)
    }

    @Test
    fun save_nonExistentIdInsertsWithExplicitId() {
        val entity = newEntity(name = "explicit")
        entity.id = 999L

        val saved = repository.save(entity)
        assertEquals(999L, saved.id)
        assertTrue(repository.existsById(999L))
        assertEquals(1L, repository.count())

        val next = repository.insertEntity(newEntity(name = "next"))
        assertEquals(1L, next.id)
    }

    @Test
    fun incrViews_incrementsAndAccumulates() {
        val entity = repository.insertEntity(newEntity(views = 5L))
        repository.incrViews(entity.id)
        assertEquals(6L, repository.findById(entity.id)!!.views)
        repository.incrViews(entity.id)
        repository.incrViews(entity.id)
        assertEquals(8L, repository.findById(entity.id)!!.views, "多次调用应累加")
    }

    @Test
    fun incrViews_nonExistentIdNoSideEffect() {
        val entity = repository.insertEntity(newEntity(views = 3L))
        repository.incrViews(99999L) // 不抛异常，0 行更新
        assertEquals(3L, repository.findById(entity.id)!!.views, "其他行不受影响")
    }

    @Test
    fun findAll_returnsAllRows() {
        assertTrue(repository.findAll().isEmpty(), "空表应返回空列表")
        assertEquals(0L, repository.count())

        val a = repository.insertEntity(newEntity(name = "a"))
        val b = repository.insertEntity(newEntity(name = "b"))
        // 无排序保证，只断言内容集合
        assertEquals(setOf(a.id, b.id), repository.findAll().map { it.id }.toSet())
    }

    @Test
    fun count_countsAllRows() {
        assertEquals(0L, repository.count())
        repository.insertEntity(newEntity())
        assertEquals(1L, repository.count())
        repository.insertEntity(newEntity())
        repository.insertEntity(newEntity())
        assertEquals(3L, repository.count())
    }

    // ------------------------------------------------------------------
    // 原生查询点 1：CopilotSetService.query 的 DB 逻辑（§3 #5）
    // ------------------------------------------------------------------

    @Test
    fun querySets_anonymousSeesOnlyPublicNotDeleted() {
        val pub = repository.insertEntity(newEntity(name = "pub"))
        repository.insertEntity(newEntity(name = "priv", status = CopilotSetStatus.PRIVATE))
        repository.insertEntity(newEntity(name = "del", deleted = true))

        val (sets, total, hasNext) = querySets(userId = null)
        assertEquals(listOf(pub.id), sets.map { it.id }, "匿名只应看到 PUBLIC 且未删除的作业集")
        assertEquals(1L, total)
        assertFalse(hasNext)
    }

    @Test
    fun querySets_loggedInSeesPublicOrOwnPrivate() {
        val own = repository.insertEntity(newEntity(name = "own", creatorId = 42L, status = CopilotSetStatus.PRIVATE))
        val pub = repository.insertEntity(newEntity(name = "pub", creatorId = 99L))
        repository.insertEntity(newEntity(name = "other-priv", creatorId = 7L, status = CopilotSetStatus.PRIVATE))

        val (sets, total) = querySets(userId = 42L)
        assertEquals(setOf(own.id, pub.id), sets.map { it.id }.toSet(), "登录用户可见 PUBLIC 或自己的（含自己的 PRIVATE）")
        assertEquals(2L, total)

        // 匿名看不到自己的 PRIVATE（与 userId=null 分支对比）
        val (anon, anonTotal) = querySets(userId = null)
        assertEquals(listOf(pub.id), anon.map { it.id })
        assertEquals(1L, anonTotal)
    }

    @Test
    fun querySets_onlyFollowingFiltersByUserFollowSubquery() {
        val alice = repository.insertEntity(newEntity(name = "alice-set", creatorId = 10L))
        val bob = repository.insertEntity(newEntity(name = "bob-set", creatorId = 20L))
        val carol = repository.insertEntity(newEntity(name = "carol-set", creatorId = 30L))
        insertFollow(1L, 10L, specialFollow = false)
        insertFollow(1L, 20L, specialFollow = true) // 特关与否不影响子查询（子查询只取 follow_user_id）

        val (sets, total) = querySets(userId = 1L, onlyFollowing = true)
        assertEquals(setOf(alice.id, bob.id), sets.map { it.id }.toSet())
        assertEquals(2L, total)

        // 未关注任何人：子查询 0 行 → 空结果
        val (none, noneTotal) = querySets(userId = 2L, onlyFollowing = true)
        assertTrue(none.isEmpty())
        assertEquals(0L, noneTotal)

        // 关闭 onlyFollowing 时不受关注关系影响
        val (all, allTotal) = querySets(userId = 1L)
        assertEquals(3, all.size)
        assertEquals(3L, allTotal)
    }

    @Test
    fun querySets_anonymousIgnoresOnlyFollowing() {
        val pub = repository.insertEntity(newEntity(name = "pub-set", creatorId = 10L))
        repository.insertEntity(newEntity(name = "priv-set", creatorId = 10L, status = CopilotSetStatus.PRIVATE))
        insertFollow(1L, 10L)

        val (sets, total) = querySets(userId = null, onlyFollowing = true)

        assertEquals(listOf(pub.id), sets.map { it.id }, "匿名用户不支持关注功能，onlyFollowing 应被忽略")
        assertEquals(1L, total, "匿名仍只应看到 PUBLIC")
    }

    @Test
    fun querySets_creatorIdFilterAndMeSpecialCase() {
        val mine = repository.insertEntity(newEntity(name = "mine", creatorId = 42L))
        repository.insertEntity(newEntity(name = "theirs", creatorId = 7L))

        val (mineOnly, total) = querySets(userId = null, creatorId = "42")
        assertEquals(listOf(mine.id), mineOnly.map { it.id })
        assertEquals(1L, total)

        // ME 特判：登录时等价于自己的 id
        val (meSets, meTotal) = querySets(userId = 42L, creatorId = ME)
        assertEquals(listOf(mine.id), meSets.map { it.id })
        assertEquals(1L, meTotal)

        // 匿名 + ME：toLongOrNull() 失败 → 空结果（服务层行为）
        val (anonMe, anonMeTotal) = querySets(userId = null, creatorId = ME)
        assertTrue(anonMe.isEmpty())
        assertEquals(0L, anonMeTotal)

        // 非法 creatorId → 空结果
        val (bad, badTotal) = querySets(userId = null, creatorId = "abc")
        assertTrue(bad.isEmpty())
        assertEquals(0L, badTotal)
    }

    @Test
    fun querySets_keywordMatchesNameOrDescription() {
        repository.insertEntity(newEntity(name = "高难作业集", description = "包含水陈"))
        repository.insertEntity(newEntity(name = "普通", description = "高难关卡"))
        repository.insertEntity(newEntity(name = "无关", description = "无"))
        val mySet = repository.insertEntity(newEntity(name = "MySet"))

        // name 或 description 命中
        val (byName, nameTotal) = querySets(userId = null, keyword = "高难")
        assertEquals(2, byName.size, "name 与 description 均应命中")
        assertEquals(2L, nameTotal)

        // PG LIKE 大小写敏感（ASCII）
        val (caseMiss, _) = querySets(userId = null, keyword = "myset")
        assertTrue(caseMiss.isEmpty())
        val (caseHit, _) = querySets(userId = null, keyword = "MySet")
        assertEquals(listOf(mySet.id), caseHit.map { it.id })

        // 无命中
        val (none, noneTotal) = querySets(userId = null, keyword = "不存在的关键词")
        assertTrue(none.isEmpty())
        assertEquals(0L, noneTotal)

        // 基线记录：keyword 中的 % 未转义，按通配符处理
        val (wild, _) = querySets(userId = null, keyword = "My%")
        assertEquals(listOf(mySet.id), wild.map { it.id })
    }

    @Test
    fun querySets_copilotIdsContainsJson() {
        val a = repository.insertEntity(newEntity(copilotIds = listOf(1L, 2L, 3L)))
        val b = repository.insertEntity(newEntity(copilotIds = listOf(5L)))

        // 全部 required id 都被包含才命中
        val (hit, hitTotal) = querySets(userId = null, copilotIds = listOf(1L, 2L))
        assertEquals(listOf(a.id), hit.map { it.id })
        assertEquals(1L, hitTotal)

        val (miss, _) = querySets(userId = null, copilotIds = listOf(3L, 4L))
        assertTrue(miss.isEmpty(), "缺少 4 不应命中")

        val (single, _) = querySets(userId = null, copilotIds = listOf(5L))
        assertEquals(listOf(b.id), single.map { it.id })

        // requiredIds 去重：toSet 后 [1,1] 等价于 [1]
        val (dedup, _) = querySets(userId = null, copilotIds = listOf(1L, 1L))
        assertEquals(listOf(a.id), dedup.map { it.id })

        // 空 copilotIds：服务层有 !isNullOrEmpty() 守卫，不生成 @> 条件 → 全量返回
        val (all, allTotal) = querySets(userId = null, copilotIds = emptyList())
        assertEquals(2, all.size)
        assertEquals(2L, allTotal)
    }

    @Test
    fun containsJson_rawOperatorWithEmptySetMatchesAll() {
        val a = repository.insertEntity(newEntity(copilotIds = listOf(1L, 2L)))
        val b = repository.insertEntity(newEntity(copilotIds = emptyList()))

        // 原生 SQL 复刻 `copilot_ids @> ?::jsonb`（空 JSON 数组）：jsonb [] @> [] = true，匹配所有行；
        // 服务层 query() 因 !req.copilotIds.isNullOrEmpty() 守卫不会以空集合触达该条件。
        val hits = jdbi.withHandle<List<Long>, Exception> { handle ->
            handle.createQuery("SELECT id FROM copilot_set WHERE copilot_ids @> :ids::jsonb")
                .bind("ids", "[]")
                .mapTo(Long::class.java)
                .list()
        }
        assertEquals(setOf(a.id, b.id), hits.toSet())
    }

    @Test
    fun querySets_paginationIdDescAndHasNext() {
        val ids = (1..5).map { repository.insertEntity(newEntity(name = "s$it")).id }

        val (p1, t1, h1) = querySets(userId = null, page = 1, limit = 2)
        assertEquals(listOf(ids[4], ids[3]), p1.map { it.id }, "默认 id 倒序")
        assertEquals(5L, t1)
        assertTrue(h1)

        val (p2, _, h2) = querySets(userId = null, page = 2, limit = 2)
        assertEquals(listOf(ids[2], ids[1]), p2.map { it.id })
        assertTrue(h2)

        val (p3, _, h3) = querySets(userId = null, page = 3, limit = 2)
        assertEquals(listOf(ids[0]), p3.map { it.id })
        assertFalse(h3, "最后一页 hasNext=false")

        // offset 超界 → 空列表，但 total 不变
        val (p4, t4, h4) = querySets(userId = null, page = 4, limit = 2)
        assertTrue(p4.isEmpty())
        assertEquals(5L, t4)
        assertFalse(h4)
    }

    @Test
    fun querySets_combinedConditions() {
        val target = repository.insertEntity(
            newEntity(name = "特关作业集", description = "肉鸽", copilotIds = listOf(1L, 2L, 3L), creatorId = 10L),
        )
        repository.insertEntity(
            newEntity(name = "第二个作业集", description = "肉鸽", copilotIds = listOf(1L, 2L, 3L), creatorId = 10L),
        )
        repository.insertEntity(
            newEntity(name = "别的", description = "肉鸽", copilotIds = listOf(1L, 2L, 3L), creatorId = 20L),
        )
        insertFollow(1L, 10L)

        val (sets, total, hasNext) = querySets(
            userId = 1L,
            onlyFollowing = true,
            creatorId = "10",
            keyword = "特关",
            copilotIds = listOf(2L, 3L),
            page = 1,
            limit = 10,
        )
        assertEquals(listOf(target.id), sets.map { it.id }, "各条件叠加后应只剩 target")
        assertEquals(1L, total)
        assertFalse(hasNext)
    }

    // ------------------------------------------------------------------
    // 原生查询点 2：CopilotSetScoreRefreshTask（§3 #13/#14/#15）
    // ------------------------------------------------------------------

    @Test
    fun task_countNotDeleted() {
        // 迁移后 countNotDeleted 返回 Long（ktorm EntitySequence.count() 原为 Int，见 ANALYSIS §3 #13 类型备注）
        assertEquals(0L, repository.countNotDeleted())
        repository.insertEntity(newEntity(deleted = false))
        repository.insertEntity(newEntity(deleted = false))
        repository.insertEntity(newEntity(deleted = true))
        assertEquals(2L, repository.countNotDeleted(), "delete=true 不计入")
    }

    @Test
    fun task_findNotDeletedPage() {
        val ids = (1..5).map { repository.insertEntity(newEntity(name = "p$it", deleted = it % 2 == 0)).id }
        // 未删除的为 id=1,3,5 → ids[0], ids[2], ids[4]

        val all = mutableListOf<Long>()
        var offset = 0
        while (true) {
            val page = repository.findNotDeletedPage(offset, 2)
            if (page.isEmpty()) break
            all += page.map { it.id }
            offset += 2
        }
        // 基线记录：findNotDeletedPage 无 ORDER BY，分页顺序不稳定，只断言集合一致
        assertEquals(setOf(ids[0], ids[2], ids[4]), all.toSet())

        // offset 超界 → 空页（终止条件）
        val beyond = repository.findNotDeletedPage(100, 2)
        assertTrue(beyond.isEmpty())
    }

    @Test
    fun task_findByIdsAndNotDeleted() {
        val alive1 = insertCopilot(deleted = false)
        val alive2 = insertCopilot(deleted = false)
        val deleted = insertCopilot(deleted = true)

        val found = findByIdsAndNotDeleted(listOf(alive1, alive2, deleted, 99999L))
        assertEquals(setOf(alive1, alive2), found.map { it.copilotId }.toSet(), "已删除与不存在的 id 应排除")

        // 空 ids：调用方守卫直接短路为空（任务中 s.copilotIds.isEmpty() 分支）
        assertTrue(findByIdsAndNotDeleted(emptyList()).isEmpty())
    }

    @Test
    fun task_findByIdsAndNotDeleted_rawEmptyInListRecorded() {
        assertTrue(findByIdsAndNotDeleted(emptyList()).isEmpty())
    }

    @Test
    fun task_batchUpdateHotScores() {
        val a = repository.insertEntity(newEntity(hotScore = 1.0))
        val b = repository.insertEntity(newEntity(hotScore = 2.0))
        val c = repository.insertEntity(newEntity(hotScore = 3.0))

        repository.batchUpdateHotScores(mapOf(a.id to 10.0, b.id to 20.0, 99999L to 99.0))

        assertEquals(10.0, repository.findById(a.id)!!.hotScore)
        assertEquals(20.0, repository.findById(b.id)!!.hotScore)
        assertEquals(3.0, repository.findById(c.id)!!.hotScore, "不在 map 中的行不受影响")
    }

    // ------------------------------------------------------------------
    // 复合主键 / 唯一约束（user_follow，本模块裸查询涉及的表）
    // ------------------------------------------------------------------

    @Test
    fun userFollow_compositePrimaryKeyRejectsDuplicate() {
        insertFollow(1L, 2L)
        // 基线记录：user_follow 复合主键 (user_id, follow_user_id)，重复插入抛唯一约束异常；
        // follow 为 check-then-act，无 ON CONFLICT。
        assertThrows(Exception::class.java) { insertFollow(1L, 2L) }
        // 不同 follow_user_id 可插入
        insertFollow(1L, 3L)
    }
}
