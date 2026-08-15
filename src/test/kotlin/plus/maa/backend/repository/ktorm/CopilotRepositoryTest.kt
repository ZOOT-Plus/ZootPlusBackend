package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import plus.maa.backend.service.model.RatingCount
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

/**
 * `CopilotRepository` 全量集成测试。
 *
 * 覆盖：
 *  - repository 全部 public 方法（findAll/count/save/getNotDeletedQuery/countNotDeleted/findNotDeletedPage）；
 *  - 服务层原生查询点（全部只测 DB 部分）：
 *      1. `CopilotService.upload` / `update` 的干员批量写入与先删后插（insertOperators/replaceOperators）；
 *      2. `CopilotScoreRefreshTask.refresh` 的 batchUpdateHotScores + RatingRepository 聚合
 *         （热度公式 getHotScore 与 arkLevelService 关卡冷却为纯逻辑，不覆盖）；
 *      3. `SegmentService.afterPropertiesSet` 的全量扫描（findAllNotDeletedIdTitleDetails；
 *         IK 分词与内存 INDEX 为纯内存逻辑，不覆盖）；
 *      4. `CopilotService.query` 的复合条件分页查询（queryCopilots()，含 onlyFollowing /
 *         includeOps / notIncludeOps 子查询、三键两向排序、分页与总数）。
 *
 * 未覆盖点及原因：
 *  - `CopilotService.upload/query/edit` 完整流程：依赖 RedisCache、SensitiveWordService、SegmentService、
 *    SiteMessageService、ArkLevelService、InternalComposeCache 等 12 个服务，无法脱离 Spring 上下文构造，
 *    只测到 repository 层 + DB 原生查询点；
 *  - `CopilotScoreRefreshTask.refreshHotScores/refreshTop100HotScores`：Redis 热度榜与分页循环未覆盖
 *    （redisCache 依赖），其 DB 部分（batchUpdateHotScores + countByTypeKeyInRatingAfter + countNotDeleted/findNotDeletedPage）已覆盖；
 *  - `CopilotService.query` 的批量取用户：user 表归属 user 模块（复用 UserRepository.findAllById）；
 *  - `RatingRepository` 各方法：归属 rating 模块（本文件只使用 rating 表数据喂聚合）。
 *
 * 行为记录：
 * 1. batchInsert/batchUpdate 空 items 为 no-op；
 * 2. `save` 对已存在实体执行全列 UPDATE（基线行为保留）；`updateEntity` 保持 flushChanges 语义
 *    （只更新变化的列，通过 repository 内快照比对实现）；
 * 3. `stageName like keyword` 不带 % 通配符（= 精确匹配语义）；
 * 4. query 的 hasNext 两分支语义不同：聚合分支 `count > page*limit`，非聚合分支 `r.size >= limit`。
 */
class CopilotRepositoryTest : TestDbSupport() {

    private val repo = CopilotRepository(jdbi)
    private val ratingRepo = RatingRepository(jdbi)

    // ---------- 测试数据工厂 ----------

    private val t0: LocalDateTime = LocalDateTime.of(2024, 1, 1, 0, 0)
    private val t1: LocalDateTime = LocalDateTime.of(2024, 1, 2, 0, 0)
    private val t2: LocalDateTime = LocalDateTime.of(2024, 1, 3, 0, 0)
    private val t3: LocalDateTime = LocalDateTime.of(2024, 1, 4, 0, 0)

    /**
     * 全列工厂。copilotId 默认 null → 0（INSERT 时由 bigserial 生成并回填）；
     * 传非空值则显式写入该 id（基线行为，见类注释 #3）。
     */
    private fun newCopilot(
        copilotId: Long? = null,
        type: CopilotType = CopilotType.PRTS,
        stageName: String = "1-7",
        uploaderId: Long = 1L,
        views: Long = 0L,
        ratingLevel: Int = 3,
        ratingRatio: Double = 1.5,
        likeCount: Long = 0L,
        dislikeCount: Long = 0L,
        hotScore: Double = 0.0,
        title: String = "default-title",
        details: String? = null,
        firstUploadTime: LocalDateTime = t0,
        uploadTime: LocalDateTime = t0,
        content: String = "{}",
        status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
        commentStatus: CommentStatus = CommentStatus.ENABLED,
        delete: Boolean = false,
        deleteTime: LocalDateTime? = null,
        notification: Boolean = false,
    ): CopilotEntity = CopilotEntity(
        copilotId = copilotId ?: 0L,
        type = type,
        stageName = stageName,
        uploaderId = uploaderId,
        views = views,
        ratingLevel = ratingLevel,
        ratingRatio = ratingRatio,
        likeCount = likeCount,
        dislikeCount = dislikeCount,
        hotScore = hotScore,
        title = title,
        details = details,
        firstUploadTime = firstUploadTime,
        uploadTime = uploadTime,
        content = content,
        status = status,
        commentStatus = commentStatus,
        delete = delete,
        deleteTime = deleteTime,
        notification = notification,
    )

    /** 直接 SQL 插入 rating 行（rating 表归属 rating 模块，测试不依赖其 Ktorm 实体）。 */
    private fun insertRating(key: String, userId: String, rating: String, rateTime: LocalDateTime, type: String = "COPILOT") {
        jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO rating (type, key, user_id, rating, rate_time) VALUES (:type, :key, :userId, :rating, :rateTime)",
            )
                .bind("type", type)
                .bind("key", key)
                .bind("userId", userId)
                .bind("rating", rating)
                .bind("rateTime", rateTime)
                .execute()
        }
    }

    /** 按 copilot_id 查 copilot_operator 的 name（ORDER BY name）。 */
    private fun operatorNamesOf(copilotId: Long): List<String> = jdbi.withHandle<List<String>, Exception> { h ->
        h.createQuery("SELECT name FROM copilot_operator WHERE copilot_id = :copilotId ORDER BY name")
            .bind("copilotId", copilotId)
            .mapTo(String::class.java)
            .list()
    }

    /**
     * `CopilotService.query` 的 DB 部分（docs/migration-analysis.md §3 #2，收敛到
     * CopilotRepository.queryCopilots）。返回 (当前页实体列表, 过滤后总数)。
     */
    private fun queryCopilots(
        type: CopilotType? = null,
        status: CopilotSetStatus? = null,
        stageNameKeyword: String? = null,
        stageNames: List<String>? = null,
        inUserIds: List<Long>? = null,
        inCopilotIds: List<Long>? = null,
        onlyFollowing: Boolean = false,
        userId: Long? = null,
        includeOps: List<String>? = null,
        notIncludeOps: List<String>? = null,
        orderBy: String = "id",
        desc: Boolean = true,
        page: Int = 1,
        limit: Int = 10,
    ): Pair<List<CopilotEntity>, Long> = repo.queryCopilots(
        CopilotQueryRequest(
            type = type,
            status = status,
            stageNameKeyword = stageNameKeyword,
            stageNames = stageNames,
            inUserIds = inUserIds,
            inCopilotIds = inCopilotIds,
            onlyFollowingUserId = if (onlyFollowing) userId else null,
            includeOps = includeOps,
            notIncludeOps = notIncludeOps,
            orderBy = orderBy,
            desc = desc,
            page = page,
            limit = limit,
        ),
    )

    // ---------- getNotDeletedQuery / countNotDeleted / findNotDeletedPage ----------

    @Test
    fun `getNotDeletedQuery only contains delete=false rows`() {
        // 空表边界
        assertEquals(0, repo.getNotDeletedQuery().size)

        val keep1 = repo.insertEntity(newCopilot(title = "keep1"))
        val keep2 = repo.insertEntity(newCopilot(title = "keep2"))
        val deleted = repo.insertEntity(newCopilot(title = "deleted"))
        deleted.delete = true
        repo.updateEntity(deleted)

        // delete=NULL 行：data class 非空 Boolean 无法写入 NULL，用原生 SQL 显式插入 NULL 以验证 = FALSE 语义
        // （schema 已将 delete 默认值修正为 false，省略列会写入 false 而非 NULL，故必须显式指定 NULL）
        val nullDeleteId: Long
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO copilot (stage_name, uploader_id, views, rating_level, rating_ratio, title, content, \"delete\") " +
                        "VALUES ('null-delete', 1, 0, 0, 0.0, 't', '{}', NULL)",
                )
                stmt.executeQuery("SELECT copilot_id FROM copilot WHERE stage_name = 'null-delete'").use { rs ->
                    rs.next()
                    nullDeleteId = rs.getLong(1)
                }
            }
        }

        val ids = repo.getNotDeletedQuery().map { it.copilotId }.toSet()
        assertEquals(setOf(keep1.copilotId, keep2.copilotId), ids)
        assertFalse(ids.contains(deleted.copilotId), "delete=true 应被排除")
        assertFalse(ids.contains(nullDeleteId), "delete=NULL 应被排除（= FALSE 不匹配 NULL）")
        assertEquals(2, repo.getNotDeletedQuery().size)
    }

    @Test
    fun `getNotDeletedQuery count and take-drop paging matches task usage`() {
        repeat(3) {
            repo.insertEntity(newCopilot(title = "p-$it"))
        }
        val deleted = repo.insertEntity(newCopilot(title = "deleted"))
        deleted.delete = true
        repo.updateEntity(deleted)

        // CopilotScoreRefreshTask.refreshHotScores 的用法：countNotDeleted() + findNotDeletedPage(offset, pageSize)
        assertEquals(3, repo.countNotDeleted())

        val page1 = repo.findNotDeletedPage(offset = 0, limit = 2)
        assertEquals(2, page1.size)
        val page2 = repo.findNotDeletedPage(offset = 2, limit = 2)
        assertEquals(1, page2.size)
        assertTrue((page1.map { it.copilotId } + page2.map { it.copilotId }).toSet().size == 3)
    }

    // ---------- findNotDeletedCopilotId ----------

    @Test
    fun `findNotDeletedCopilotId only matches delete=false`() {
        val keep = repo.insertEntity(newCopilot(title = "keep"))
        val deleted = repo.insertEntity(newCopilot(title = "deleted"))
        deleted.delete = true
        repo.updateEntity(deleted)

        assertEquals(keep.copilotId, repo.findNotDeletedCopilotId(keep.copilotId)!!.copilotId)
        assertNull(repo.findNotDeletedCopilotId(deleted.copilotId), "delete=true 不应命中")
        assertNull(repo.findNotDeletedCopilotId(999L), "不存在返回 null")

        // delete=NULL 行：schema 默认值已修正为 false，须显式插入 NULL 以验证 = FALSE 不匹配 NULL
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO copilot (stage_name, uploader_id, views, rating_level, rating_ratio, title, content, \"delete\") " +
                        "VALUES ('null-delete', 1, 0, 0, 0.0, 't', '{}', NULL)",
                )
                stmt.executeQuery("SELECT copilot_id FROM copilot WHERE stage_name = 'null-delete'").use { rs ->
                    rs.next()
                    assertNull(repo.findNotDeletedCopilotId(rs.getLong(1)), "delete=NULL 不应命中")
                }
            }
        }
    }

    // ---------- findByCopilotId / existsByCopilotId / findByIdsAndNotDeleted ----------

    @Test
    fun `findByCopilotId hits even deleted rows`() {
        val keep = repo.insertEntity(newCopilot(title = "keep"))
        val deleted = repo.insertEntity(newCopilot(title = "deleted"))
        deleted.delete = true
        repo.updateEntity(deleted)

        assertEquals(keep.copilotId, repo.findByCopilotId(keep.copilotId)!!.copilotId)
        assertEquals(deleted.copilotId, repo.findByCopilotId(deleted.copilotId)!!.copilotId, "findByCopilotId 不过滤 delete")
        assertNull(repo.findByCopilotId(999L))
    }

    @Test
    fun `existsByCopilotId true and false`() {
        // 空表边界
        assertFalse(repo.existsByCopilotId(1L))
        val copilot = repo.insertEntity(newCopilot())
        assertTrue(repo.existsByCopilotId(copilot.copilotId))
        assertFalse(repo.existsByCopilotId(copilot.copilotId + 1000L))
    }

    @Test
    fun `findByIdsAndNotDeleted filters deleted and short-circuits empty ids`() {
        val keep1 = repo.insertEntity(newCopilot(title = "k1"))
        val keep2 = repo.insertEntity(newCopilot(title = "k2"))
        val deleted = repo.insertEntity(newCopilot(title = "d"))
        deleted.delete = true
        repo.updateEntity(deleted)

        val ids = repo.findByIdsAndNotDeleted(listOf(keep1.copilotId, keep2.copilotId, deleted.copilotId, 999L))
        assertEquals(setOf(keep1.copilotId, keep2.copilotId), ids.map { it.copilotId }.toSet(), "已删除/不存在排除")
        assertEquals(emptyList<CopilotEntity>(), repo.findByIdsAndNotDeleted(emptyList()))
    }

    // ---------- insertEntity ----------

    @Test
    fun `insertEntity backfills id and round-trips all columns`() {
        val details = "doctor, ifrit, nightingale"
        val entity = repo.insertEntity(
            newCopilot(
                type = CopilotType.VIDEO,
                stageName = "2-8",
                uploaderId = 42L,
                views = 123L,
                ratingLevel = 5,
                ratingRatio = 4.5,
                likeCount = 7L,
                dislikeCount = 2L,
                hotScore = 3.14,
                title = "video-title",
                details = details,
                firstUploadTime = t1,
                uploadTime = t2,
                content = "{\"stage\":\"2-8\"}",
                status = CopilotSetStatus.PRIVATE,
                commentStatus = CommentStatus.DISABLED,
                delete = false,
                deleteTime = null,
                notification = true,
            ),
        )

        assertTrue(entity.copilotId > 0, "自增主键应回填")
        val loaded = repo.findByCopilotId(entity.copilotId)!!
        assertEquals(entity.copilotId, loaded.copilotId)
        assertEquals(CopilotType.VIDEO, loaded.type)
        assertEquals("2-8", loaded.stageName)
        assertEquals(42L, loaded.uploaderId)
        assertEquals(123L, loaded.views)
        assertEquals(5, loaded.ratingLevel)
        assertEquals(4.5, loaded.ratingRatio, 1e-9)
        assertEquals(7L, loaded.likeCount)
        assertEquals(2L, loaded.dislikeCount)
        assertEquals(3.14, loaded.hotScore, 1e-9)
        assertEquals("video-title", loaded.title)
        assertEquals(details, loaded.details)
        assertEquals(t1, loaded.firstUploadTime)
        assertEquals(t2, loaded.uploadTime)
        assertEquals("{\"stage\":\"2-8\"}", loaded.content)
        assertEquals(CopilotSetStatus.PRIVATE, loaded.status)
        assertEquals(CommentStatus.DISABLED, loaded.commentStatus)
        assertEquals(false, loaded.delete)
        assertNull(loaded.deleteTime, "可空列 delete_time 插入 NULL 读回 null")
        assertEquals(true, loaded.notification)

        val raw = jdbi.withHandle<Triple<String, String, String>, Exception> { h ->
            h.createQuery("SELECT type, status, comment_status FROM copilot WHERE copilot_id = :copilotId")
                .bind("copilotId", entity.copilotId)
                .map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }
                .one()
        }
        assertEquals(Triple("VIDEO", "PRIVATE", "DISABLED"), raw)
    }

    @Test
    fun `insertEntity unset columns fall back to DB defaults`() {
        // 只赋 NOT NULL 且无默认值的列；其余列走 data class 默认值（与 DB 默认值一致）
        val entity = repo.insertEntity(
            CopilotEntity(
                stageName = "defaults",
                uploaderId = 1L,
                views = 0L,
                ratingLevel = 0,
                ratingRatio = 0.0,
                title = "defaults",
                content = "{}",
            ),
        )
        val loaded = repo.findByCopilotId(entity.copilotId)!!
        assertEquals(CopilotType.PRTS, loaded.type, "type 默认 'PRTS'")
        assertEquals(CopilotSetStatus.PUBLIC, loaded.status, "status 默认 'PUBLIC'")
        assertEquals(CommentStatus.ENABLED, loaded.commentStatus, "comment_status 默认 'ENABLED'")
        assertEquals(0L, loaded.likeCount)
        assertEquals(0L, loaded.dislikeCount)
        assertEquals(0.0, loaded.hotScore, 1e-9)
        assertNull(loaded.details, "details 不赋值 → 落库 NULL")
        assertNull(loaded.deleteTime, "delete_time 不赋值 → 落库 NULL")
    }

    @Test
    fun `insertEntity sequential ids 1 2 3 after truncate`() {
        assertEquals(1L, repo.insertEntity(newCopilot(title = "a")).copilotId)
        assertEquals(2L, repo.insertEntity(newCopilot(title = "b")).copilotId)
        assertEquals(3L, repo.insertEntity(newCopilot(title = "c")).copilotId)
    }

    // ---------- updateEntity ----------

    @Test
    fun `updateEntity flushChanges only writes changed columns`() {
        val entity = repo.insertEntity(newCopilot(stageName = "1-7", title = "orig"))
        val id = entity.copilotId

        // 绕过实体直接改库，模拟并发/外部修改
        jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE copilot SET stage_name = :stageName WHERE copilot_id = :copilotId")
                .bind("stageName", "direct-edit")
                .bind("copilotId", id)
                .execute()
        }

        entity.title = "changed"
        repo.updateEntity(entity)

        val loaded = repo.findByCopilotId(id)!!
        assertEquals("direct-edit", loaded.stageName, "flushChanges 只更新变化的列，不应把内存中的旧 stage_name 写回")
        assertEquals("changed", loaded.title)
    }

    @Test
    fun `updateEntity flips delete flag and findNotDeletedCopilotId misses`() {
        val entity = repo.insertEntity(newCopilot(title = "to-delete"))
        entity.delete = true
        entity.deleteTime = t1
        repo.updateEntity(entity)

        assertNull(repo.findNotDeletedCopilotId(entity.copilotId), "delete false→true 后不应命中")
        val loaded = repo.findByCopilotId(entity.copilotId)!!
        assertTrue(loaded.delete)
        assertEquals(t1, loaded.deleteTime)
    }

    // ---------- findById / deleteById / existsById（Any 参数） ----------

    @Test
    fun `findById accepts Long and String ids`() {
        val copilot = repo.insertEntity(newCopilot(title = "id-test"))
        assertEquals(copilot.copilotId, repo.findById(copilot.copilotId)!!.copilotId)
        assertEquals(copilot.copilotId, repo.findById(copilot.copilotId.toString())!!.copilotId, "字符串 id 也可命中")
        assertNull(repo.findById(999L))
        assertNull(repo.findById(0L), "id=0 未命中")
    }

    @Test
    fun `existsById true false and string id`() {
        val copilot = repo.insertEntity(newCopilot())
        assertTrue(repo.existsById(copilot.copilotId))
        assertTrue(repo.existsById(copilot.copilotId.toString()))
        assertFalse(repo.existsById(999L))
        assertFalse(repo.existsById("999"))
    }

    // ---------- findAll / count ----------

    @Test
    fun `findAll returns all rows including deleted and count matches`() {
        // 空表边界
        assertEquals(0L, CopilotRepository(jdbi).count())

        val a = repo.insertEntity(newCopilot(title = "a"))
        val b = repo.insertEntity(newCopilot(title = "b"))
        val c = repo.insertEntity(newCopilot(title = "c"))
        c.delete = true
        repo.updateEntity(c)

        assertEquals(setOf(a.copilotId, b.copilotId, c.copilotId), repo.findAll().map { it.copilotId }.toSet())
        assertEquals(3L, repo.count())
    }

    // ---------- findAllByUploadTimeAfterOrDeleteTimeAfter ----------

    @Test
    fun `findAllByUploadTimeAfterOrDeleteTimeAfter matches either time with gte boundary`() {
        // 空表边界
        assertEquals(emptyList<CopilotEntity>(), CopilotRepository(jdbi).findAllByUploadTimeAfterOrDeleteTimeAfter(t0, t0))

        // A：仅 upload_time 满足；B：仅 delete_time 满足；C：都不满足
        val a = repo.insertEntity(newCopilot(title = "a", uploadTime = t1, deleteTime = null))
        val b = repo.insertEntity(newCopilot(title = "b", uploadTime = t0, deleteTime = t2))
        repo.insertEntity(newCopilot(title = "c", uploadTime = t0, deleteTime = null))

        val result = repo.findAllByUploadTimeAfterOrDeleteTimeAfter(t1, t2)
        assertEquals(setOf(a.copilotId, b.copilotId), result.map { it.copilotId }.toSet())

        // 边界相等时间命中（gte 语义）：upload_time=t1 恰好等于下界
        assertEquals(setOf(a.copilotId, b.copilotId), repo.findAllByUploadTimeAfterOrDeleteTimeAfter(t1, t1).map { it.copilotId }.toSet())
        // delete_time=t2 恰好等于下界 → 仅 b 命中
        assertEquals(setOf(b.copilotId), repo.findAllByUploadTimeAfterOrDeleteTimeAfter(t2, t1).map { it.copilotId }.toSet())
        // delete_time=NULL 且 upload_time 未超 → 排除；b 的 delete_time=t2 低于下界 t3 也不命中
        assertEquals(emptySet<Long>(), repo.findAllByUploadTimeAfterOrDeleteTimeAfter(t3, t3).map { it.copilotId }.toSet())
    }

    // ---------- incrViews ----------

    @Test
    fun `incrViews increments atomically and accumulates`() {
        val copilot = repo.insertEntity(newCopilot(views = 10L))
        repo.incrViews(copilot.copilotId)
        assertEquals(11L, repo.findByCopilotId(copilot.copilotId)!!.views)
        repo.incrViews(copilot.copilotId)
        repo.incrViews(copilot.copilotId)
        assertEquals(13L, repo.findByCopilotId(copilot.copilotId)!!.views)
    }

    @Test
    fun `incrViews on missing id is a no-op`() {
        repo.insertEntity(newCopilot(title = "x"))
        // 不存在的 id：0 行更新，不报错
        repo.incrViews(999L)
        assertEquals(1L, repo.count())
    }

    // ---------- save ----------

    @Test
    fun `save new entity inserts and backfills id`() {
        val saved = repo.save(newCopilot(title = "fresh"))
        assertTrue(saved.copilotId > 0, "新实体（id=0）应 insert 并回填")
        assertNotNull(repo.findByCopilotId(saved.copilotId))
        assertEquals(1L, repo.count())
    }

    @Test
    fun `save with non-existent explicit id inserts that id and does not advance sequence`() {
        val saved = repo.save(newCopilot(copilotId = 5L, title = "explicit-5"))
        assertEquals(5L, saved.copilotId, "显式赋值的主键按该值插入，不回填")
        assertTrue(repo.existsById(5L))
        assertEquals(5L, repo.findById(5L)!!.copilotId)

        val fresh = repo.insertEntity(newCopilot(title = "fresh"))
        assertEquals(1L, fresh.copilotId)
        assertEquals(2L, repo.count())
    }

    @Test
    fun `save existing entity updates in place without new row and performs full-column update`() {
        val entity = repo.insertEntity(newCopilot(stageName = "1-7", title = "orig"))
        val id = entity.copilotId

        // 绕过实体直接改库
        jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE copilot SET stage_name = :stageName WHERE copilot_id = :copilotId")
                .bind("stageName", "direct-edit")
                .bind("copilotId", id)
                .execute()
        }
        entity.title = "changed"
        repo.save(entity)

        // save 对已存在实体是全列 UPDATE（基线 Ktorm entities.update 语义，非 flushChanges 脏检查）
        val loaded = repo.findByCopilotId(id)!!
        assertEquals("1-7", loaded.stageName, "save 是全列 UPDATE：内存中旧的 stage_name 覆盖了 direct-edit")
        assertEquals("changed", loaded.title)
        assertEquals(1L, repo.count(), "不应新增行")
    }

    // ---------- 原生查询点 1：CopilotService.upload / update 的干员写入（§3 #1/#4） ----------

    @Test
    fun `upload batchInsertOperators writes all operator rows for the copilot`() {
        val copilot = repo.insertEntity(newCopilot(title = "upload"))
        val opers = listOf("Eyjafjalla", "SilverAsh", "Skadi")

        // 对应 CopilotService.upload 的干员批量写入
        repo.insertOperators(copilot.copilotId, opers)

        assertEquals(opers.sorted(), operatorNamesOf(copilot.copilotId))
        // 不影响其他作业的干员
        val other = repo.insertEntity(newCopilot(title = "other"))
        assertEquals(emptyList<String>(), operatorNamesOf(other.copilotId))
    }

    @Test
    fun `upload batchInsertOperators with empty list is guarded no-op`() {
        val copilot = repo.insertEntity(newCopilot(title = "no-op"))
        // 生产代码：if (!opers.isNullOrEmpty()) 才调用 —— 空列表直接跳过（repository 内再兜底一次）
        val opers: List<String> = emptyList()
        repo.insertOperators(copilot.copilotId, opers)
        assertEquals(emptyList<String>(), operatorNamesOf(copilot.copilotId))
    }

    @Test
    fun `edit replaceOperators deletes then batch-inserts`() {
        val copilot = repo.insertEntity(newCopilot(title = "edit"))
        repo.insertOperators(copilot.copilotId, listOf("Eyjafjalla", "SilverAsh"))
        assertEquals(listOf("Eyjafjalla", "SilverAsh"), operatorNamesOf(copilot.copilotId))

        // 对应 CopilotService.update 的先删后插
        repo.replaceOperators(copilot.copilotId, listOf("Skadi", "Nightingale"))
        assertEquals(listOf("Nightingale", "Skadi"), operatorNamesOf(copilot.copilotId), "旧干员应被替换")

        // 空列表 → 只删除
        repo.replaceOperators(copilot.copilotId, emptyList())
        assertEquals(emptyList<String>(), operatorNamesOf(copilot.copilotId))
    }

    // ---------- 原生查询点 2：CopilotScoreRefreshTask 的 batchUpdateHotScores + countByTypeKeyInRatingAfter（§3 #11/#12） ----------

    @Test
    fun `scoreRefreshTask batchUpdateHotScores writes per-row hot scores`() {
        val c1 = repo.insertEntity(newCopilot(title = "b1", hotScore = 0.0))
        val c2 = repo.insertEntity(newCopilot(title = "b2", hotScore = 0.0))
        val c3 = repo.insertEntity(newCopilot(title = "b3", hotScore = 0.0))

        // 模拟 refresh：先取出实体、内存改 hotScore，再批量写回
        val loaded = listOf(repo.findByCopilotId(c1.copilotId)!!, repo.findByCopilotId(c2.copilotId)!!)
        loaded[0].hotScore = 9.5
        loaded[1].hotScore = 8.25
        repo.batchUpdateHotScores(loaded.associate { it.copilotId to it.hotScore })

        assertEquals(9.5, repo.findByCopilotId(c1.copilotId)!!.hotScore, 1e-9)
        assertEquals(8.25, repo.findByCopilotId(c2.copilotId)!!.hotScore, 1e-9)
        assertEquals(0.0, repo.findByCopilotId(c3.copilotId)!!.hotScore, 1e-9, "未在 batch 中的行不受影响")
    }

    @Test
    fun `scoreRefreshTask counts aggregates ratings by key with filters`() {
        // 数据设计：
        //   key=100: LIKE ×3（u1@t1、u2@t2、u5@t1）+ DISLIKE ×1（u3@t1）
        //   key=101: LIKE ×1（u1@t1）
        //   key=102: LIKE ×1（u1@t0，早于 startTime）
        //   COMMENT 类型 LIKE ×1（key=100，应被 type 过滤）
        insertRating(key = "100", userId = "u1", rating = "LIKE", rateTime = t1)
        insertRating(key = "100", userId = "u2", rating = "LIKE", rateTime = t2)
        insertRating(key = "100", userId = "u5", rating = "LIKE", rateTime = t1)
        insertRating(key = "100", userId = "u3", rating = "DISLIKE", rateTime = t1)
        insertRating(key = "101", userId = "u1", rating = "LIKE", rateTime = t1)
        insertRating(key = "102", userId = "u1", rating = "LIKE", rateTime = t0)
        insertRating(key = "100", userId = "u4", rating = "LIKE", rateTime = t1, type = "COMMENT")

        // 对应 CopilotScoreRefreshTask.counts（由 RatingRepository 实现，startTime=t1）
        val result = ratingRepo.countByTypeKeyInRatingAfter(
            Rating.KeyType.COPILOT,
            listOf("100", "101", "102"),
            RatingType.LIKE,
            t1,
        )

        val countMap = result.associate { it.key to it.count }
        assertEquals(3L, countMap["100"], "边界相等时间（gte）计入；COMMENT 类型与 DISLIKE 不计")
        assertEquals(1L, countMap["101"])
        assertFalse(countMap.containsKey("102"), "早于 startTime 的行不计入，key 不出现在分组结果中")

        // 枚举落库为名字
        val rawRating = jdbi.withHandle<String, Exception> { h ->
            h.createQuery("SELECT rating FROM rating WHERE key = '100' AND user_id = 'u3'")
                .mapTo(String::class.java)
                .one()
        }
        assertEquals("DISLIKE", rawRating)

        // startTime 提前到 t0 → key=102 计入
        val all = ratingRepo.countByTypeKeyInRatingAfter(
            Rating.KeyType.COPILOT,
            listOf("100", "101", "102"),
            RatingType.LIKE,
            t0,
        ).associate { it.key to it.count }
        assertEquals(1L, all["102"])
    }

    @Test
    fun `scoreRefreshTask counts with empty keys short-circuits to empty list`() {
        insertRating(key = "100", userId = "u1", rating = "LIKE", rateTime = t1)
        assertEquals(
            emptyList<RatingCount>(),
            ratingRepo.countByTypeKeyInRatingAfter(Rating.KeyType.COPILOT, emptyList(), RatingType.LIKE, t0),
        )
    }

    // ---------- 原生查询点 3：SegmentService.afterPropertiesSet 的全量扫描（§3 #9） ----------

    @Test
    fun `segmentService index scan only reads not-deleted copilots with title and details`() {
        val keep1 = repo.insertEntity(newCopilot(title = "t1", details = "d1"))
        val keep2 = repo.insertEntity(newCopilot(title = "t2", details = null))
        val deleted = repo.insertEntity(newCopilot(title = "t3", details = "d3"))
        deleted.delete = true
        repo.updateEntity(deleted)

        // 对应 SegmentService.afterPropertiesSet（索引构建只读三列）
        val scanned = repo.findAllNotDeletedIdTitleDetails()

        val byId = scanned.associateBy { it.copilotId }
        assertEquals(setOf(keep1.copilotId, keep2.copilotId), byId.keys, "delete=true 的作业不进索引")
        assertEquals("t1", byId[keep1.copilotId]!!.title)
        assertEquals("d1", byId[keep1.copilotId]!!.details)
        assertEquals("t2", byId[keep2.copilotId]!!.title)
        assertNull(byId[keep2.copilotId]!!.details, "details 可空，分词时按 null 处理")
    }

    // ---------- 原生查询点 4：CopilotService.query 复合条件分页查询（§3 #2） ----------

    /** 构造 query 测试数据集：返回 title → id 映射。 */
    private fun seedQueryData(): Map<String, Long> {
        val ids = mutableMapOf<String, Long>()
        ids["c1"] = repo.insertEntity(
            newCopilot(title = "c1", type = CopilotType.PRTS, stageName = "1-7", uploaderId = 10L, views = 100L, hotScore = 1.0),
        ).copilotId
        ids["c2"] = repo.insertEntity(
            newCopilot(
                title = "c2",
                type = CopilotType.VIDEO,
                stageName = "2-8",
                uploaderId = 11L,
                views = 200L,
                hotScore = 2.0,
                status = CopilotSetStatus.PRIVATE,
            ),
        ).copilotId
        ids["c3"] = repo.insertEntity(
            newCopilot(title = "c3", type = CopilotType.PRTS, stageName = "1-7", uploaderId = 11L, views = 300L, hotScore = 3.0),
        ).copilotId
        ids["c4"] = repo.insertEntity(
            newCopilot(
                title = "c4",
                type = CopilotType.PRTS,
                stageName = "3-9",
                uploaderId = 12L,
                views = 400L,
                hotScore = 4.0,
                delete = true,
            ),
        ).copilotId
        ids["c5"] = repo.insertEntity(
            newCopilot(title = "c5", type = CopilotType.PRTS, stageName = "1-7", uploaderId = 13L, views = 500L, hotScore = 5.0),
        ).copilotId
        ids["c6"] = repo.insertEntity(
            newCopilot(title = "c6", type = CopilotType.VIDEO, stageName = "4-10", uploaderId = 10L, views = 600L, hotScore = 6.0),
        ).copilotId

        // 干员：c1[Eyjafjalla, SilverAsh] c3[Eyjafjalla] c5[Skadi] c6[Eyjafjalla]
        repo.insertOperators(ids["c1"]!!, listOf("Eyjafjalla", "SilverAsh"))
        repo.insertOperators(ids["c3"]!!, listOf("Eyjafjalla"))
        repo.insertOperators(ids["c5"]!!, listOf("Skadi"))
        repo.insertOperators(ids["c6"]!!, listOf("Eyjafjalla"))
        // 关注：10 关注了 11、13
        jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO user_follow (user_id, follow_user_id, special_follow, updated_at) " +
                    "VALUES (:userId, :followUserId, :specialFollow, :updatedAt)",
            )
                .bind("userId", 10L).bind("followUserId", 11L).bind("specialFollow", false).bind("updatedAt", t0)
                .execute()
            h.createUpdate(
                "INSERT INTO user_follow (user_id, follow_user_id, special_follow, updated_at) " +
                    "VALUES (:userId, :followUserId, :specialFollow, :updatedAt)",
            )
                .bind("userId", 10L).bind("followUserId", 13L).bind("specialFollow", false).bind("updatedAt", t0)
                .execute()
        }
        return ids
    }

    @Test
    fun `query base filter excludes deleted rows`() {
        seedQueryData()
        val (rows, total) = queryCopilots()
        assertEquals(setOf("c1", "c2", "c3", "c5", "c6"), rows.map { it.title }.toSet(), "c4(delete=true) 应排除")
        assertEquals(5L, total)
    }

    @Test
    fun `query filters by type and status`() {
        seedQueryData()
        assertEquals(setOf("c1", "c3", "c5"), queryCopilots(type = CopilotType.PRTS).first.map { it.title }.toSet())
        assertEquals(setOf("c1", "c3", "c5", "c6"), queryCopilots(status = CopilotSetStatus.PUBLIC).first.map { it.title }.toSet())
        assertEquals(setOf("c6"), queryCopilots(type = CopilotType.VIDEO, status = CopilotSetStatus.PUBLIC).first.map { it.title }.toSet())
        // type=VIDEO 且 status=PRIVATE → 仅 c2
        assertEquals(setOf("c2"), queryCopilots(type = CopilotType.VIDEO, status = CopilotSetStatus.PRIVATE).first.map { it.title }.toSet())
    }

    @Test
    fun `query filters by stage name keyword and stage name list`() {
        seedQueryData()
        // 基线行为：like 不带 % 通配符，等于精确匹配
        assertEquals(setOf("c1", "c3", "c5"), queryCopilots(stageNameKeyword = "1-7").first.map { it.title }.toSet())
        assertEquals(emptyList<CopilotEntity>(), queryCopilots(stageNameKeyword = "7").first, "无 % 包裹时 '7' 不是 '1-7' 的子串")
        assertEquals(
            setOf("c1", "c3", "c5", "c6"),
            queryCopilots(stageNames = listOf("1-7", "4-10")).first.map { it.title }.toSet(),
        )
    }

    @Test
    fun `query filters by uploader ids and copilot ids`() {
        val ids = seedQueryData()
        // c6 的 uploaderId=10 ∈ [10,11]，也会命中
        assertEquals(setOf("c1", "c2", "c3", "c6"), queryCopilots(inUserIds = listOf(10L, 11L)).first.map { it.title }.toSet())
        assertEquals(setOf("c2", "c6"), queryCopilots(inCopilotIds = listOf(ids["c2"]!!, ids["c6"]!!)).first.map { it.title }.toSet())
    }

    @Test
    fun `query onlyFollowing uses user_follow subquery`() {
        seedQueryData()
        // 10 关注了 11、13 → uploader ∈ {11, 13} → c2, c3, c5
        assertEquals(
            setOf("c2", "c3", "c5"),
            queryCopilots(onlyFollowing = true, userId = 10L).first.map { it.title }.toSet(),
        )
        // 未传入 userId（游客）→ onlyFollowing 条件不生效，回落到 delete=false 全集
        assertEquals(5, queryCopilots(onlyFollowing = true, userId = null).first.size)
        // 无关注记录的用户 → 空结果
        assertEquals(emptyList<CopilotEntity>(), queryCopilots(onlyFollowing = true, userId = 99L).first)
    }

    @Test
    fun `query include and exclude operators use copilot_operator subquery`() {
        seedQueryData()
        assertEquals(setOf("c1", "c3", "c6"), queryCopilots(includeOps = listOf("Eyjafjalla")).first.map { it.title }.toSet())
        assertEquals(
            setOf("c2", "c5"),
            queryCopilots(notIncludeOps = listOf("Eyjafjalla")).first.map {
                it.title
            }.toSet(),
            "c1/c3/c6 含 Eyjafjalla 排除，c4 已删除",
        )
        assertEquals(setOf("c5"), queryCopilots(includeOps = listOf("Skadi")).first.map { it.title }.toSet())
    }

    @Test
    fun `query combines all conditions with AND`() {
        seedQueryData()
        // type=PRTS + status=PUBLIC + include Eyjafjalla + notInclude SilverAsh → 仅 c3
        val (rows, total) = queryCopilots(
            type = CopilotType.PRTS,
            status = CopilotSetStatus.PUBLIC,
            includeOps = listOf("Eyjafjalla"),
            notIncludeOps = listOf("SilverAsh"),
        )
        assertEquals(listOf("c3"), rows.map { it.title })
        assertEquals(1L, total)

        // onlyFollowing + stageNameKeyword → uploader ∈ {11,13} 且 stage=1-7 → c3, c5
        assertEquals(
            setOf("c3", "c5"),
            queryCopilots(onlyFollowing = true, userId = 10L, stageNameKeyword = "1-7").first.map { it.title }.toSet(),
        )

        // 无任何条件匹配 → 空结果 + total=0
        val (emptyRows, emptyTotal) = queryCopilots(type = CopilotType.PRTS, inUserIds = listOf(999L))
        assertEquals(emptyList<CopilotEntity>(), emptyRows)
        assertEquals(0L, emptyTotal)
    }

    @Test
    fun `query orders by hot views id and direction`() {
        seedQueryData()
        // hot desc（默认 desc）
        assertEquals(listOf("c6", "c5", "c3", "c2", "c1"), queryCopilots(orderBy = "hot").first.map { it.title })
        // views asc
        assertEquals(listOf("c1", "c2", "c3", "c5", "c6"), queryCopilots(orderBy = "views", desc = false).first.map { it.title })
        // id asc / 未知 key 回落到 id
        assertEquals(listOf("c1", "c2", "c3", "c5", "c6"), queryCopilots(orderBy = "id", desc = false).first.map { it.title })
        assertEquals(listOf("c1", "c2", "c3", "c5", "c6"), queryCopilots(orderBy = "unknown", desc = false).first.map { it.title })
        // hot desc 分页 + total 不受分页影响
        val (page, total) = queryCopilots(orderBy = "hot", page = 1, limit = 2)
        assertEquals(listOf("c6", "c5"), page.map { it.title })
        assertEquals(5L, total)
    }

    @Test
    fun `query pagination boundaries and hasNext semantics`() {
        seedQueryData()
        // 越界页 → 空列表，total 仍为 5
        val (emptyPage, total) = queryCopilots(orderBy = "hot", page = 99, limit = 2)
        assertEquals(emptyList<CopilotEntity>(), emptyPage)
        assertEquals(5L, total)
        // 最后一页恰好一条
        assertEquals(listOf("c1"), queryCopilots(orderBy = "hot", page = 3, limit = 2).first.map { it.title })

        // 基线行为：hasNext 两分支语义不同。
        // 聚合分支（keyword/levelKeyword/uploaderId/operator/copilotIds 全空时才走）：
        //   hasNext = count > page * limit
        // 非聚合分支：
        //   hasNext = r.size >= limit
        // 数据共 5 条：limit=5、page=1 时 r.size=5、count=5
        val (allRows, allTotal) = queryCopilots(orderBy = "hot", page = 1, limit = 5)
        assertEquals(5, allRows.size)
        val pageXLimit = 1 * 5
        val aggHasNext = allTotal > pageXLimit // 5 > 5 → false
        val nonAggHasNext = allRows.size >= 5 // 5 >= 5 → true
        assertFalse(aggHasNext)
        assertTrue(nonAggHasNext)
        // 服务层两条分支对同一数据给出相反结论（基线行为）
        assertEquals(false, aggHasNext)
        assertEquals(true, nonAggHasNext)
    }
}
