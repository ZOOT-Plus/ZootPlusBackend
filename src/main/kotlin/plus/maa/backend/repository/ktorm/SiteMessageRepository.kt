package plus.maa.backend.repository.ktorm

import org.jdbi.v3.core.Jdbi
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
import plus.maa.backend.repository.entity.SiteMessageEntity
import java.time.LocalDateTime

interface SiteMessageDao {

    @SqlUpdate(
        """
        INSERT INTO site_message (receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at)
        VALUES (:receiverId, :senderId, :senderName, :type, :title, :content, :copilotId, :readAt, :createdAt)
        """,
    )
    @GetGeneratedKeys("id")
    @AllowUnusedBindings
    fun insert(@BindKotlin entity: SiteMessageEntity): Long

    /** save 语义：实体携带非零 id 且库中不存在该 id 时，按显式 id 插入（基线：Ktorm add 全列插入）。 */
    @SqlUpdate(
        """
        INSERT INTO site_message (id, receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at)
        VALUES (:id, :receiverId, :senderId, :senderName, :type, :title, :content, :copilotId, :readAt, :createdAt)
        """,
    )
    fun insertWithExplicitId(@BindKotlin entity: SiteMessageEntity)

    /**
     * 一条 SQL 完成对作者所有特关粉丝的「作业发布」站内信批量写入（INSERT...SELECT），
     * 避免把粉丝列表与消息实体全部载入应用内存。返回受影响行数（即通知人数，可为 0）。
     */
    @SqlUpdate(
        """
        INSERT INTO site_message
          (receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at)
        SELECT uf.user_id, :senderId, :senderName, 'COPILOT_PUBLISHED', :title, :content, :copilotId, NULL, :createdAt
        FROM user_follow uf
        WHERE uf.follow_user_id = :senderId AND uf.special_follow = TRUE
        """,
    )
    fun insertCopilotPublishedNotifications(
        @Bind("senderId") senderId: Long,
        @Bind("senderName") senderName: String,
        @Bind("copilotId") copilotId: Long,
        @Bind("title") title: String,
        @Bind("content") content: String,
        @Bind("createdAt") createdAt: LocalDateTime,
    ): Int

    /** markRead 幂等语义：仅未读（read_at IS NULL）时更新，返回更新行数（0 = 已读/不存在/不匹配）。 */
    @SqlUpdate(
        """
        UPDATE site_message SET read_at = :readAt
        WHERE id = :id AND receiver_id = :receiverId AND read_at IS NULL
        """,
    )
    fun markRead(@Bind("receiverId") receiverId: Long, @Bind("id") id: Long, @Bind("readAt") readAt: LocalDateTime): Int

    @SqlUpdate(
        """
        UPDATE site_message SET read_at = :readAt
        WHERE receiver_id = :receiverId AND read_at IS NULL
        """,
    )
    fun markAllRead(@Bind("receiverId") receiverId: Long, @Bind("readAt") readAt: LocalDateTime): Int

    @SqlQuery(
        """
        SELECT id, receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at
        FROM site_message
        WHERE id = :id
        """,
    )
    fun findById(@Bind("id") id: Long): SiteMessageEntity?

    @SqlQuery("SELECT EXISTS(SELECT 1 FROM site_message WHERE id = :id)")
    fun existsById(@Bind("id") id: Long): Boolean

    @SqlUpdate("DELETE FROM site_message WHERE id = :id")
    fun deleteById(@Bind("id") id: Long): Int

    @SqlQuery("SELECT COUNT(*) FROM site_message")
    fun count(): Long

    /** 无排序（基线：Ktorm `entities.toList()` 无 ORDER BY）。 */
    @SqlQuery(
        """
        SELECT id, receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at
        FROM site_message
        """,
    )
    fun findAll(): List<SiteMessageEntity>

    @SqlQuery("SELECT COUNT(*) FROM site_message WHERE receiver_id = :receiverId AND read_at IS NULL")
    fun countUnreadByReceiverId(@Bind("receiverId") receiverId: Long): Long

    /** 全列 SET（本项目更新路径均为整对象读写，未改列写入原值，行为等价）。 */
    @SqlUpdate(
        """
        UPDATE site_message SET
            receiver_id = :receiverId,
            sender_id = :senderId,
            sender_name = :senderName,
            type = :type,
            title = :title,
            content = :content,
            copilot_id = :copilotId,
            read_at = :readAt,
            created_at = :createdAt
        WHERE id = :id
        """,
    )
    fun update(@BindKotlin entity: SiteMessageEntity): Int
}

/**
 * 站内信 Repository。内部全部手写 SQL：固定语句走 [SiteMessageDao]，
 * 条件分页（unreadOnly 动态 WHERE）用 handle.createQuery + mapTo(data class)。
 */
@Repository
class SiteMessageRepository(
    private val jdbi: Jdbi,
) {

    private val dao: SiteMessageDao = jdbi.onDemand(SiteMessageDao::class.java)

    fun insertCopilotPublishedNotifications(
        senderId: Long,
        senderName: String,
        copilotId: Long,
        title: String,
        content: String,
        createdAt: LocalDateTime,
    ): Int = dao.insertCopilotPublishedNotifications(senderId, senderName, copilotId, title, content, createdAt)

    /**
     * receiver 过滤 + 可选未读过滤，ORDER BY created_at DESC, id DESC（同 created_at 时 id 大者在前），
     * COUNT 一次 + LIMIT/OFFSET 一次，返回 Spring [Page]。
     */
    fun findByReceiverId(receiverId: Long, unreadOnly: Boolean, pageable: Pageable): Page<SiteMessageEntity> {
        val unreadClause = if (unreadOnly) " AND read_at IS NULL" else ""
        return jdbi.withHandle<Page<SiteMessageEntity>, Exception> { handle ->
            val total = handle.createQuery(
                "SELECT COUNT(*) FROM site_message WHERE receiver_id = :receiverId$unreadClause",
            )
                .bind("receiverId", receiverId)
                .mapTo(Long::class.java)
                .one()
            val data = handle.createQuery(
                """
                SELECT id, receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at
                FROM site_message
                WHERE receiver_id = :receiverId$unreadClause
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """.trimIndent(),
            )
                .bind("receiverId", receiverId)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .mapTo(SiteMessageEntity::class.java)
                .list()
            PageImpl(data, pageable, total)
        }
    }

    fun countUnreadByReceiverId(receiverId: Long): Long = dao.countUnreadByReceiverId(receiverId)

    /** 已读时返回 false（幂等语义），read_at 不被覆盖。 */
    fun markRead(receiverId: Long, id: Long, readAt: LocalDateTime): Boolean = dao.markRead(receiverId, id, readAt) > 0

    fun markAllRead(receiverId: Long, readAt: LocalDateTime): Int = dao.markAllRead(receiverId, readAt)

    fun findById(id: Any): SiteMessageEntity? = dao.findById(id as Long)

    fun deleteById(id: Any): Boolean = dao.deleteById(id as Long) > 0

    fun existsById(id: Any): Boolean = dao.existsById(id as Long)

    fun count(): Long = dao.count()

    fun findAll(): List<SiteMessageEntity> = dao.findAll()

    fun insertEntity(entity: SiteMessageEntity): SiteMessageEntity {
        if (entity.id == 0L) {
            entity.id = dao.insert(entity)
        } else {
            dao.insertWithExplicitId(entity)
        }
        return entity
    }

    fun updateEntity(entity: SiteMessageEntity): SiteMessageEntity {
        dao.update(entity)
        return entity
    }

    /** isNewEntity 语义（基线）：id == 0L 或库中不存在该 id → insert；否则全列 UPDATE。 */
    fun save(entity: SiteMessageEntity): SiteMessageEntity =
        if (entity.id == 0L || !existsById(entity.id)) insertEntity(entity) else updateEntity(entity)
}
