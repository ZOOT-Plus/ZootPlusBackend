package plus.maa.backend.repository.ktorm

import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.Query
import org.jdbi.v3.core.statement.Update
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings
import org.jdbi.v3.sqlobject.customizer.Bind
import org.jdbi.v3.sqlobject.kotlin.BindKotlin
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys
import org.jdbi.v3.sqlobject.statement.SqlBatch
import org.jdbi.v3.sqlobject.statement.SqlUpdate
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.OperatorEntity
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块「copilot」的 Jdbi repository。
 *
 * - `getNotDeletedQuery` 返回 `List<CopilotEntity>`，调用方仅本模块的
 *   [plus.maa.backend.task.CopilotScoreRefreshTask]；
 * - `updateEntity` 保持 Ktorm `flushChanges()` 语义：只更新"相对上次落库快照"变化的列
 *   （快照按 copilot_id 记录在 [snapshots] 中，实体读回/写入时打点）。
 */
@Repository
class CopilotRepository(private val jdbi: Jdbi) {

    private val dao: CopilotDao = jdbi.onDemand(CopilotDao::class.java)

    /**
     * `updateEntity` 的 flushChanges 语义快照：copilotId → 实体上次"落库/从库读回"时的状态。
     *
     * 打点时机：所有实体读回方法（find* / query* / getNotDeletedQuery 等）、`insertEntity`（insert 后）、`save`（写后）。
     * `updateEntity` 将实体当前状态与快照逐列比对，只 SET 变化的列（与 Ktorm flushChanges 一致）。
     * 未在快照中的实体（非本 repository 读出的）退化为全列 UPDATE。
     *
     * 注意：快照按 id 而非对象同一性存储，同一 copilot 的两次并发读会互相覆盖快照；
     * 由于快照内容都是"该行某时刻的读回状态"，对本项目的使用模式（读后立刻改再 update）行为与基线一致。
     */
    private val snapshots = ConcurrentHashMap<Long, CopilotEntity>()

    // ------------------------------------------------------------------
    // 实体读回
    // ------------------------------------------------------------------

    /** 全部未删除作业（delete=false）；无排序（基线行为）。 */
    fun getNotDeletedQuery(): List<CopilotEntity> = queryEntities(
        "SELECT * FROM copilot WHERE \"delete\" = FALSE",
    )

    /** 未删除作业总数。 */
    fun countNotDeleted(): Long = jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT COUNT(*) FROM copilot WHERE \"delete\" = FALSE")
            .mapTo(Long::class.java)
            .one()
    }

    /**
     * 未删除作业分页。
     * 必须带 ORDER BY copilot_id：无排序的 LIMIT/OFFSET 在页间发生 UPDATE 后行序漂移，
     * 导致重复/遗漏行（CopilotScoreRefreshTask 分页循环踩坑）。
     */
    fun findNotDeletedPage(offset: Int, limit: Int): List<CopilotEntity> = queryEntities(
        "SELECT * FROM copilot WHERE \"delete\" = FALSE ORDER BY copilot_id LIMIT ? OFFSET ?",
        limit,
        offset,
    )

    fun findNotDeletedCopilotId(copilotId: Long): CopilotEntity? = queryEntity(
        "SELECT * FROM copilot WHERE copilot_id = ? AND \"delete\" = FALSE LIMIT 1",
        copilotId,
    )

    /** 不过滤 delete（delete=true 的行也命中）。 */
    fun findByCopilotId(copilotId: Long): CopilotEntity? = queryEntity(
        "SELECT * FROM copilot WHERE copilot_id = ?",
        copilotId,
    )

    fun existsByCopilotId(copilotId: Long): Boolean = jdbi.withHandle<Boolean, Exception> { h ->
        h.createQuery("SELECT EXISTS(SELECT 1 FROM copilot WHERE copilot_id = ?)")
            .bind(0, copilotId)
            .mapTo(Boolean::class.java)
            .one()
    }

    /** 按 id 集合取未删除作业（空集合直接返回空列表）。 */
    fun findByIdsAndNotDeleted(ids: List<Long>): List<CopilotEntity> {
        if (ids.isEmpty()) return emptyList()
        return queryEntities(
            "SELECT * FROM copilot WHERE copilot_id IN (${placeholders(ids)}) AND \"delete\" = FALSE",
            *ids.toTypedArray(),
        )
    }

    /**
     * SegmentService 索引构建用：只读 copilot_id/title/details 三列。
     */
    fun findAllNotDeletedIdTitleDetails(): List<CopilotIndexRow> = jdbi.withHandle<List<CopilotIndexRow>, Exception> { h ->
        h.createQuery("SELECT copilot_id, title, details FROM copilot WHERE \"delete\" = FALSE")
            .mapTo(CopilotIndexRow::class.java)
            .list()
    }

    fun findAllByUploadTimeAfterOrDeleteTimeAfter(uploadTimeAfter: LocalDateTime, deleteTimeAfter: LocalDateTime): List<CopilotEntity> =
        queryEntities(
            "SELECT * FROM copilot WHERE upload_time >= ? OR delete_time >= ?",
            uploadTimeAfter,
            deleteTimeAfter,
        )

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 全列插入。copilotId==0 → 由 bigserial 生成并回填；
     * copilotId != 0 → 显式按该值插入（基线行为：不推进序列）。
     */
    fun insertEntity(copilot: CopilotEntity): CopilotEntity {
        if (copilot.copilotId == 0L) {
            copilot.copilotId = dao.insert(copilot)
        } else {
            dao.insertWithId(copilot)
        }
        snapshots[copilot.copilotId] = copilot.copy()
        return copilot
    }

    /**
     * 保持 Ktorm `flushChanges()` 语义：与上次落库/读回快照逐列比对，只 UPDATE 变化的列；
     * 无快照（非本 repository 读出的实体）退化为全列 UPDATE；无变化列时跳过 SQL（0 行更新）。
     */
    fun updateEntity(copilot: CopilotEntity): CopilotEntity {
        val snapshot = snapshots[copilot.copilotId]
        if (snapshot == null) {
            dao.updateAll(copilot)
        } else {
            val sets = mutableListOf<String>()
            val values = mutableListOf<Any?>()
            for ((column, getter) in COLUMNS) {
                val value = getter(copilot)
                if (value != getter(snapshot)) {
                    sets += "$column = ?"
                    values += value
                }
            }
            if (sets.isNotEmpty()) {
                jdbi.useHandle<Exception> { h ->
                    h.createUpdate("UPDATE copilot SET ${sets.joinToString(", ")} WHERE copilot_id = ?")
                        .bindAll(values)
                        .bind(values.size, copilot.copilotId)
                        .execute()
                }
            }
        }
        snapshots[copilot.copilotId] = copilot.copy()
        return copilot
    }

    /** 基类语义：id==0L 或 DB 不存在 → insert；否则全列 UPDATE（基线 Ktorm entities.update 为全列 SET）。 */
    fun save(entity: CopilotEntity): CopilotEntity {
        return if (entity.copilotId == 0L || !existsById(entity.copilotId)) {
            insertEntity(entity)
        } else {
            dao.updateAll(entity)
            snapshots[entity.copilotId] = entity.copy()
            entity
        }
    }

    fun existsById(id: Any): Boolean = existsByCopilotId(id.toString().toLong())

    fun findById(id: Any): CopilotEntity? = findByCopilotId(id.toString().toLong())

    /** 全部行（含已删除），无排序（基类 findAll 语义）。 */
    fun findAll(): List<CopilotEntity> = queryEntities("SELECT * FROM copilot")

    /** 全部行数（基类 count 语义）。 */
    fun count(): Long = jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT COUNT(*) FROM copilot")
            .mapTo(Long::class.java)
            .one()
    }

    fun incrViews(id: Long) {
        jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE copilot SET views = views + 1 WHERE copilot_id = ?")
                .bind(0, id)
                .execute()
        }
    }

    // ------------------------------------------------------------------
    // 原生查询点
    // ------------------------------------------------------------------

    /**
     * CopilotService.upload 用：干员批量插入。空列表 no-op。
     */
    fun insertOperators(copilotId: Long, names: List<String>) {
        if (names.isEmpty()) return
        dao.batchInsertOperators(names.map { OperatorEntity(copilotId = copilotId, name = it) })
    }

    /**
     * CopilotService.update 用：先删后插（DELETE + @SqlBatch INSERT）。空列表 → 只删除。
     */
    fun replaceOperators(copilotId: Long, names: List<String>) {
        jdbi.useTransaction<Exception> { h ->
            h.createUpdate("DELETE FROM copilot_operator WHERE copilot_id = ?")
                .bind(0, copilotId)
                .execute()
            if (names.isNotEmpty()) {
                dao.batchInsertOperators(names.map { OperatorEntity(copilotId = copilotId, name = it) })
            }
        }
    }

    /**
     * CopilotService.query 的复合条件分页查询（delete=false 基条件 +
     * 可选 type/status/stage_name LIKE/stage_name IN/uploader_id IN/copilot_id IN/
     * 关注子查询/干员包含排除子查询 + 三键两向排序 + LIMIT/OFFSET + COUNT）。
     *
     * 返回 (当前页实体, 过滤后总数)。空集合条件跳过。
     * `stageNameKeyword` 原样绑定（不带 % 通配符，基线 like 语义）。
     */
    fun queryCopilots(req: CopilotQueryRequest): Pair<List<CopilotEntity>, Long> {
        val conds = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        fun add(expr: String, vararg values: Any?) {
            conds += expr
            values.forEach { args += it }
        }

        fun addIn(column: String, values: List<*>) {
            if (values.isNotEmpty()) {
                add("$column IN (${placeholders(values)})", *values.toTypedArray())
            }
        }

        fun addOperatorSubquery(names: List<String>, not: Boolean) {
            if (names.isNotEmpty()) {
                val op = if (not) "NOT IN" else "IN"
                add(
                    "copilot_id $op (SELECT copilot_id FROM copilot_operator WHERE name IN (${placeholders(names)}))",
                    *names.toTypedArray(),
                )
            }
        }

        conds += "\"delete\" = FALSE"
        req.type?.let { add("type = ?", it.name) }
        req.status?.let { add("status = ?", it.name) }
        req.stageNameKeyword?.let { add("stage_name LIKE ?", it) }
        req.stageNames?.let { addIn("stage_name", it) }
        req.inUserIds?.let { addIn("uploader_id", it) }
        req.inCopilotIds?.let { addIn("copilot_id", it) }
        req.onlyFollowingUserId?.let {
            add("uploader_id IN (SELECT follow_user_id FROM user_follow WHERE user_id = ?)", it)
        }
        req.includeOps?.let { addOperatorSubquery(it, not = false) }
        req.notIncludeOps?.let { addOperatorSubquery(it, not = true) }

        val where = conds.joinToString(" AND ")
        val orderColumn = when (req.orderBy) {
            "hot" -> "hot_score"
            "views" -> "views"
            else -> "copilot_id" // "id" 与未知 key 均回落 copilot_id（基线 sortedBy fallback）
        }
        val order = "ORDER BY $orderColumn ${if (req.desc) "DESC" else "ASC"}, copilot_id ${if (req.desc) "DESC" else "ASC"}"
        val offset = (req.page - 1) * req.limit

        return jdbi.withHandle<Pair<List<CopilotEntity>, Long>, Exception> { h ->
            val rows = h.createQuery("SELECT * FROM copilot WHERE $where $order LIMIT ? OFFSET ?")
                .bindAll(args)
                .bind(args.size, req.limit)
                .bind(args.size + 1, offset)
                .mapTo(CopilotEntity::class.java)
                .list()
            val total = h.createQuery("SELECT COUNT(*) FROM copilot WHERE $where")
                .bindAll(args)
                .mapTo(Long::class.java)
                .one()
            rows.forEach { snapshots[it.copilotId] = it.copy() }
            rows to total
        }
    }

    /**
     * CopilotScoreRefreshTask.refresh 用：按 copilotId 批量写 hot_score。
     */
    fun batchUpdateHotScores(scores: Map<Long, Double>) {
        if (scores.isEmpty()) return
        dao.batchUpdateHotScores(scores.map { (copilotId, hotScore) -> HotScoreUpdate(copilotId, hotScore) })
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 列名 → 取值函数 全列清单（updateEntity 逐列比对与 SET 子句用）。 */
    private companion object ColumnMap {
        val COLUMNS: List<Pair<String, (CopilotEntity) -> Any?>> = listOf(
            "type" to { it.type },
            "stage_name" to { it.stageName },
            "uploader_id" to { it.uploaderId },
            "views" to { it.views },
            "rating_level" to { it.ratingLevel },
            "rating_ratio" to { it.ratingRatio },
            "like_count" to { it.likeCount },
            "dislike_count" to { it.dislikeCount },
            "hot_score" to { it.hotScore },
            "title" to { it.title },
            "details" to { it.details },
            "first_upload_time" to { it.firstUploadTime },
            "upload_time" to { it.uploadTime },
            "content" to { it.content },
            "status" to { it.status },
            "comment_status" to { it.commentStatus },
            "\"delete\"" to { it.delete },
            "delete_time" to { it.deleteTime },
            "notification" to { it.notification },
        )
    }

    private fun placeholders(values: List<*>): String = values.joinToString(", ") { "?" }

    private fun queryEntity(sql: String, vararg args: Any?): CopilotEntity? = jdbi.withHandle<CopilotEntity?, Exception> { h ->
        h.createQuery(sql)
            .bindAll(args.toList())
            .mapTo(CopilotEntity::class.java)
            .findOne()
            .orElse(null)
    }?.also { snapshots[it.copilotId] = it.copy() }

    private fun queryEntities(sql: String, vararg args: Any?): List<CopilotEntity> = jdbi.withHandle<List<CopilotEntity>, Exception> { h ->
        h.createQuery(sql)
            .bindAll(args.toList())
            .mapTo(CopilotEntity::class.java)
            .list()
    }.also { rows -> rows.forEach { snapshots[it.copilotId] = it.copy() } }

    private fun Query.bindAll(args: List<Any?>): Query {
        args.forEachIndexed { index, value -> bind(index, value) }
        return this
    }
}

/** 按位置绑定参数（`?` 占位符）。 */
private fun Update.bindAll(args: List<Any?>): Update {
    args.forEachIndexed { index, value -> bind(index, value) }
    return this
}

/**
 * `CopilotService.query` 复合查询的参数集。
 */
data class CopilotQueryRequest(
    val type: CopilotType? = null,
    val status: CopilotSetStatus? = null,
    /** 原样绑定（不带 % 通配符，基线 like 语义） */
    val stageNameKeyword: String? = null,
    val stageNames: List<String>? = null,
    val inUserIds: List<Long>? = null,
    val inCopilotIds: List<Long>? = null,
    /** 非 null 时附加 uploader_id IN (SELECT follow_user_id FROM user_follow WHERE user_id = ?) */
    val onlyFollowingUserId: Long? = null,
    val includeOps: List<String>? = null,
    val notIncludeOps: List<String>? = null,
    /** hot / id / views，其余值回落 copilot_id */
    val orderBy: String = "id",
    val desc: Boolean = true,
    val page: Int = 1,
    val limit: Int = 10,
)

/**
 * SegmentService 索引构建投影（只读 copilot_id/title/details 三列）。
 */
data class CopilotIndexRow(
    val copilotId: Long,
    val title: String,
    val details: String?,
)

/** batchUpdateHotScores 的单行载荷。 */
data class HotScoreUpdate(
    val copilotId: Long,
    val hotScore: Double,
)

/**
 * copilot 表 DAO（Jdbi SqlObject，运行时代理）。
 *
 * - insertWithId：显式指定 id 的插入（基线 save 语义，不推进序列）；
 * - updateAll：全列 SET（基线 Ktorm entities.update 语义，save() 使用）。
 */
interface CopilotDao {

    @SqlUpdate(
        """
        INSERT INTO copilot (type, stage_name, uploader_id, views, rating_level, rating_ratio,
            like_count, dislike_count, hot_score, title, details, first_upload_time, upload_time,
            content, status, comment_status, "delete", delete_time, notification)
        VALUES (:type, :stageName, :uploaderId, :views, :ratingLevel, :ratingRatio,
            :likeCount, :dislikeCount, :hotScore, :title, :details, :firstUploadTime, :uploadTime,
            :content, :status, :commentStatus, :delete, :deleteTime, :notification)
        """,
    )
    @GetGeneratedKeys("copilot_id")
    @AllowUnusedBindings
    fun insert(@BindKotlin copilot: CopilotEntity): Long

    @SqlUpdate(
        """
        INSERT INTO copilot (copilot_id, type, stage_name, uploader_id, views, rating_level, rating_ratio,
            like_count, dislike_count, hot_score, title, details, first_upload_time, upload_time,
            content, status, comment_status, "delete", delete_time, notification)
        VALUES (:copilotId, :type, :stageName, :uploaderId, :views, :ratingLevel, :ratingRatio,
            :likeCount, :dislikeCount, :hotScore, :title, :details, :firstUploadTime, :uploadTime,
            :content, :status, :commentStatus, :delete, :deleteTime, :notification)
        """,
    )
    fun insertWithId(@BindKotlin copilot: CopilotEntity): Int

    @SqlUpdate(
        """
        UPDATE copilot SET type = :type, stage_name = :stageName, uploader_id = :uploaderId,
            views = :views, rating_level = :ratingLevel, rating_ratio = :ratingRatio,
            like_count = :likeCount, dislike_count = :dislikeCount, hot_score = :hotScore,
            title = :title, details = :details, first_upload_time = :firstUploadTime,
            upload_time = :uploadTime, content = :content, status = :status,
            comment_status = :commentStatus, "delete" = :delete, delete_time = :deleteTime,
            notification = :notification
        WHERE copilot_id = :copilotId
        """,
    )
    fun updateAll(@BindKotlin copilot: CopilotEntity): Int

    @SqlBatch(
        "INSERT INTO copilot_operator (copilot_id, name) VALUES (:copilotId, :name)",
    )
    fun batchInsertOperators(@BindKotlin operators: List<OperatorEntity>): IntArray

    @SqlBatch(
        "UPDATE copilot SET hot_score = :hotScore WHERE copilot_id = :copilotId",
    )
    fun batchUpdateHotScores(@BindKotlin items: List<HotScoreUpdate>): IntArray
}
