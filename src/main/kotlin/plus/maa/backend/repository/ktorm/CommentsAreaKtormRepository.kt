package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.dsl.inList
import org.ktorm.dsl.isNotNull
import org.ktorm.dsl.isNull
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.count
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.toList
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.common.extensions.paginate
import plus.maa.backend.repository.entity.CommentsAreaEntity
import plus.maa.backend.repository.entity.CommentsAreas

@Repository
class CommentsAreaKtormRepository(
    database: Database,
) : KtormRepository<CommentsAreaEntity, CommentsAreas>(database, CommentsAreas) {

    fun findByMainCommentId(commentsId: Long): List<CommentsAreaEntity> {
        return entities.filter { it.mainCommentId eq commentsId }.toList()
    }

    fun findByCopilotIdAndDeleteAndMainCommentIdExists(
        copilotId: Long,
        delete: Boolean,
        exists: Boolean,
        pageable: Pageable,
    ): Page<CommentsAreaEntity> {
        val sequence = entities.filter {
            it.copilotId eq copilotId and
                (it.delete eq delete) and
                if (exists) it.mainCommentId.isNotNull() else it.mainCommentId.isNull()
        }

        return sequence.paginate(pageable)
    }

    fun findByCopilotId(copilotIds: Collection<Long>, delete: Boolean): List<CommentsAreaEntity> {
        return entities.filter {
            it.copilotId inList copilotIds and (it.delete eq delete)
        }.toList()
    }

    fun findByMainCommentId(ids: List<Long>): List<CommentsAreaEntity> {
        return entities.filter { it.mainCommentId inList ids }.toList()
    }

    fun countByCopilotId(copilotId: Long, delete: Boolean): Long {
        return entities.filter {
            it.copilotId eq copilotId and (it.delete eq delete)
        }.count().toLong()
    }

    override fun findById(id: Any): CommentsAreaEntity? {
        return entities.firstOrNull { it.id eq (id as Long) }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq (id as Long) } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq (id as Long) }
    }

    override fun getIdColumn(entity: CommentsAreaEntity): Any = entity.id

    override fun isNewEntity(entity: CommentsAreaEntity): Boolean {
        return entity.id == 0L || !existsById(entity.id)
    }

    fun insertEntity(entity: CommentsAreaEntity): CommentsAreaEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: CommentsAreaEntity): CommentsAreaEntity {
        entity.flushChanges()
        return entity
    }
}
