package plus.maa.backend.repository.ktorm

import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings
import org.jdbi.v3.sqlobject.customizer.Bind
import org.jdbi.v3.sqlobject.kotlin.BindKotlin
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys
import org.jdbi.v3.sqlobject.statement.SqlQuery
import org.jdbi.v3.sqlobject.statement.SqlUpdate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.CommentsAreaEntity
import java.time.LocalDateTime

interface CommentsAreaDao {

    @SqlUpdate(
        """
        INSERT INTO comments_area (copilot_id, from_comment_id, uploader_id, message, like_count,
                                   dislike_count, upload_time, topping, "delete", delete_time,
                                   main_comment_id, notification)
        VALUES (:copilotId, :fromCommentId, :uploaderId, :message, :likeCount,
                :dislikeCount, :uploadTime, :topping, :delete, :deleteTime,
                :mainCommentId, :notification)
        """,
    )
    @GetGeneratedKeys("id")
    @AllowUnusedBindings
    fun insert(@BindKotlin entity: CommentsAreaEntity): Long

    /** save 语义：实体携带非零 id 且库中不存在该 id 时，按显式 id 插入（基线：Ktorm add 全列插入，不推进序列）。 */
    @SqlUpdate(
        """
        INSERT INTO comments_area (id, copilot_id, from_comment_id, uploader_id, message, like_count,
                                   dislike_count, upload_time, topping, "delete", delete_time,
                                   main_comment_id, notification)
        VALUES (:id, :copilotId, :fromCommentId, :uploaderId, :message, :likeCount,
                :dislikeCount, :uploadTime, :topping, :delete, :deleteTime,
                :mainCommentId, :notification)
        """,
    )
    fun insertWithExplicitId(@BindKotlin entity: CommentsAreaEntity)

    /**
     * 全列 SET 更新（本项目更新路径均为"读出→改→写回"，未改列写入原值，行为等价）。
     */
    @SqlUpdate(
        """
        UPDATE comments_area SET
            copilot_id = :copilotId,
            from_comment_id = :fromCommentId,
            uploader_id = :uploaderId,
            message = :message,
            like_count = :likeCount,
            dislike_count = :dislikeCount,
            upload_time = :uploadTime,
            topping = :topping,
            "delete" = :delete,
            delete_time = :deleteTime,
            main_comment_id = :mainCommentId,
            notification = :notification
        WHERE id = :id
        """,
    )
    fun update(@BindKotlin entity: CommentsAreaEntity): Int

    @SqlQuery("SELECT EXISTS(SELECT 1 FROM comments_area WHERE id = :id)")
    fun existsById(@Bind("id") id: Long): Boolean

    @SqlUpdate("DELETE FROM comments_area WHERE id = :id")
    fun deleteById(@Bind("id") id: Long): Int

    /**
     * 按 main_comment_id 批量软删除子评论：仅置 "delete" 与 delete_time，
     * 不触碰其余列。用于主评论删除时一并清理所有回复，避免逐行 updateEntity 的 N 次 UPDATE。
     */
    @SqlUpdate(
        """
        UPDATE comments_area
        SET "delete" = :delete, delete_time = :deleteTime
        WHERE main_comment_id = :mainCommentId
        """,
    )
    fun softDeleteByMainCommentId(
        @Bind("mainCommentId") mainCommentId: Long,
        @Bind("delete") delete: Boolean,
        @Bind("deleteTime") deleteTime: LocalDateTime?,
    ): Int

    @SqlQuery("SELECT COUNT(*) FROM comments_area")
    fun count(): Long

    @SqlQuery("SELECT COUNT(*) FROM comments_area WHERE copilot_id = :copilotId AND \"delete\" = :delete")
    fun countByCopilotId(@Bind("copilotId") copilotId: Long, @Bind("delete") delete: Boolean): Long
}

/**
 * comments_area 表 Repository。内部全部手写 SQL：
 * - 固定语句走 [CommentsAreaDao]；
 * - 动态 IN 查询（[findByCopilotId] / [findByMainCommentId] 的集合参数）用
 *   handle.createQuery + 占位符展开 + mapTo(data class)；
 * - 分页（[findByCopilotIdAndDeleteAndMainCommentIdExists]）手写 COUNT + LIMIT/OFFSET，
 *   返回 Spring [Page]；ORDER BY id 保证跨页行序稳定。
 */
@Repository
class CommentsAreaRepository(
    private val jdbi: Jdbi,
) {

    private val dao: CommentsAreaDao = jdbi.onDemand(CommentsAreaDao::class.java)

    /** 主评论条件 `main_comment_id IS NOT NULL` / `IS NULL` 的谓词片段（exists 参数语义）。 */
    private fun mainCommentPredicate(exists: Boolean): String = if (exists) "main_comment_id IS NOT NULL" else "main_comment_id IS NULL"

    fun findByMainCommentId(commentsId: Long): List<CommentsAreaEntity> {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT id, copilot_id, from_comment_id, uploader_id, message, like_count,
                       dislike_count, upload_time, topping, "delete", delete_time,
                       main_comment_id, notification
                FROM comments_area
                WHERE main_comment_id = :mainCommentId
                """.trimIndent(),
            )
                .bind("mainCommentId", commentsId)
                .mapTo<CommentsAreaEntity>()
                .list()
        }
    }

    /**
     * 主评论分页查询：COUNT 一次 + LIMIT/OFFSET 一次，返回 Spring [Page]。
     * `exists` 参数语义：true = `main_comment_id IS NOT NULL`、false = `IS NULL`。
     * ORDER BY id（插入顺序，最接近基线无序的物理顺序，跨页稳定）。
     */
    fun findByCopilotIdAndDeleteAndMainCommentIdExists(
        copilotId: Long,
        delete: Boolean,
        exists: Boolean,
        pageable: Pageable,
    ): Page<CommentsAreaEntity> {
        val predicate = mainCommentPredicate(exists)
        return jdbi.withHandleUnchecked { handle ->
            val total = handle.createQuery(
                """
                SELECT COUNT(*) FROM comments_area
                WHERE copilot_id = :copilotId AND "delete" = :delete AND $predicate
                """.trimIndent(),
            )
                .bind("copilotId", copilotId)
                .bind("delete", delete)
                .mapTo(Long::class.java)
                .one()
            val content = handle.createQuery(
                """
                SELECT id, copilot_id, from_comment_id, uploader_id, message, like_count,
                       dislike_count, upload_time, topping, "delete", delete_time,
                       main_comment_id, notification
                FROM comments_area
                WHERE copilot_id = :copilotId AND "delete" = :delete AND $predicate
                ORDER BY id
                LIMIT :limit OFFSET :offset
                """.trimIndent(),
            )
                .bind("copilotId", copilotId)
                .bind("delete", delete)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .mapTo<CommentsAreaEntity>()
                .list()
            PageImpl(content, pageable, total)
        }
    }

    fun findByCopilotId(copilotIds: Collection<Long>, delete: Boolean): List<CommentsAreaEntity> {
        if (copilotIds.isEmpty()) {
            return emptyList()
        }
        val placeholders = copilotIds.indices.joinToString(", ") { ":id$it" }
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT id, copilot_id, from_comment_id, uploader_id, message, like_count,
                       dislike_count, upload_time, topping, "delete", delete_time,
                       main_comment_id, notification
                FROM comments_area
                WHERE copilot_id IN ($placeholders) AND "delete" = :delete
                """.trimIndent(),
            )
                .also { query -> copilotIds.forEachIndexed { index, value -> query.bind("id$index", value) } }
                .bind("delete", delete)
                .mapTo<CommentsAreaEntity>()
                .list()
        }
    }

    fun findByMainCommentId(ids: List<Long>): List<CommentsAreaEntity> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val placeholders = ids.indices.joinToString(", ") { ":id$it" }
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT id, copilot_id, from_comment_id, uploader_id, message, like_count,
                       dislike_count, upload_time, topping, "delete", delete_time,
                       main_comment_id, notification
                FROM comments_area
                WHERE main_comment_id IN ($placeholders)
                """.trimIndent(),
            )
                .also { query -> ids.forEachIndexed { index, value -> query.bind("id$index", value) } }
                .mapTo<CommentsAreaEntity>()
                .list()
        }
    }

    fun countByCopilotId(copilotId: Long, delete: Boolean): Long = dao.countByCopilotId(copilotId, delete)

    fun findById(id: Any): CommentsAreaEntity? {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT id, copilot_id, from_comment_id, uploader_id, message, like_count,
                       dislike_count, upload_time, topping, "delete", delete_time,
                       main_comment_id, notification
                FROM comments_area
                WHERE id = :id
                """.trimIndent(),
            )
                .bind("id", id as Long)
                .mapTo<CommentsAreaEntity>()
                .findFirst()
                .orElse(null)
        }
    }

    fun deleteById(id: Any): Boolean = dao.deleteById(id as Long) > 0

    /** 批量软删除指定主评论下的所有子评论，返回受影响行数。 */
    fun softDeleteByMainCommentId(mainCommentId: Long, deleteTime: LocalDateTime): Int =
        dao.softDeleteByMainCommentId(mainCommentId, true, deleteTime)

    fun existsById(id: Any): Boolean = dao.existsById(id as Long)

    fun findAll(): List<CommentsAreaEntity> {
        // 无排序（基线：Ktorm `entities.toList()` 无 ORDER BY）。
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT id, copilot_id, from_comment_id, uploader_id, message, like_count,
                       dislike_count, upload_time, topping, "delete", delete_time,
                       main_comment_id, notification
                FROM comments_area
                """.trimIndent(),
            )
                .mapTo<CommentsAreaEntity>()
                .list()
        }
    }

    fun count(): Long = dao.count()

    /** 插入并回填自增 id（原地写入传入实体，保持 ktorm `add` 的回填语义，服务层依赖 `entity.id`）。 */
    fun insertEntity(entity: CommentsAreaEntity): CommentsAreaEntity {
        if (entity.id == 0L) {
            entity.id = dao.insert(entity)
        } else {
            // 基线行为：显式 id 按该值插入，不推进自增序列（测试 insertEntityWithExplicitIdInsertsAsIsAndDuplicatePkConflicts）
            dao.insertWithExplicitId(entity)
        }
        return entity
    }

    fun updateEntity(entity: CommentsAreaEntity): CommentsAreaEntity {
        dao.update(entity)
        return entity
    }

    /** isNewEntity 语义（基线）：id == 0L 或库中不存在该 id → insert；否则全列 UPDATE。 */
    fun save(entity: CommentsAreaEntity): CommentsAreaEntity =
        if (entity.id == 0L || !existsById(entity.id)) insertEntity(entity) else updateEntity(entity)
}
