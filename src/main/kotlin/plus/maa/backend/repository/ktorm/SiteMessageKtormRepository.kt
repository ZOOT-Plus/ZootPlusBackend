package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.batchInsert
import org.ktorm.dsl.desc
import org.ktorm.dsl.eq
import org.ktorm.dsl.isNull
import org.ktorm.dsl.update
import org.ktorm.dsl.where
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.sortedBy
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.SiteMessageEntity
import plus.maa.backend.repository.entity.SiteMessages
import java.time.LocalDateTime

@Repository
class SiteMessageKtormRepository(
    database: Database,
) : KtormRepository<SiteMessageEntity, SiteMessages>(database, SiteMessages) {

    fun insertAll(messages: List<SiteMessageEntity>) {
        if (messages.isEmpty()) return
        database.batchInsert(SiteMessages) {
            messages.forEach { message ->
                item {
                    set(it.receiverId, message.receiverId)
                    set(it.senderId, message.senderId)
                    set(it.senderName, message.senderName)
                    set(it.type, message.type)
                    set(it.title, message.title)
                    set(it.content, message.content)
                    set(it.copilotId, message.copilotId)
                    set(it.readAt, message.readAt)
                    set(it.createdAt, message.createdAt)
                }
            }
        }
    }

    fun findByReceiverId(receiverId: Long, unreadOnly: Boolean, pageable: Pageable): Page<SiteMessageEntity> {
        var sequence = entities.filter { it.receiverId eq receiverId }
        if (unreadOnly) {
            sequence = sequence.filter { it.readAt.isNull() }
        }
        val total = sequence.count().toLong()
        val data = sequence
            .sortedBy({ it.createdAt.desc() }, { it.id.desc() })
            .drop(pageable.offset.toInt())
            .take(pageable.pageSize)
            .toList()
        return PageImpl(data, pageable, total)
    }

    fun countUnreadByReceiverId(receiverId: Long): Long {
        return entities.filter {
            (it.receiverId eq receiverId) and it.readAt.isNull()
        }.count().toLong()
    }

    fun markRead(receiverId: Long, id: Long, readAt: LocalDateTime): Boolean {
        val updatedRows = database.update(SiteMessages) {
            set(it.readAt, readAt)
            where {
                (it.id eq id) and
                    (it.receiverId eq receiverId) and
                    it.readAt.isNull()
            }
        }
        return updatedRows > 0
    }

    fun markAllRead(receiverId: Long, readAt: LocalDateTime): Int {
        return database.update(SiteMessages) {
            set(it.readAt, readAt)
            where {
                (it.receiverId eq receiverId) and it.readAt.isNull()
            }
        }
    }

    override fun findById(id: Any): SiteMessageEntity? {
        return entities.firstOrNull { it.id eq (id as Long) }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq (id as Long) } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq (id as Long) }
    }

    override fun getIdColumn(entity: SiteMessageEntity): Any = entity.id

    override fun isNewEntity(entity: SiteMessageEntity): Boolean {
        return entity.id == 0L || !existsById(entity.id)
    }

    fun insertEntity(entity: SiteMessageEntity): SiteMessageEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: SiteMessageEntity): SiteMessageEntity {
        entity.flushChanges()
        return entity
    }
}
