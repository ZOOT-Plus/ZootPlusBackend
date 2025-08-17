package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.dsl.gte
import org.ktorm.dsl.inList
import org.ktorm.dsl.notEq
import org.ktorm.dsl.or
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.toList
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.common.extensions.paginate
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Copilots
import java.time.LocalDateTime
import java.util.stream.Stream

@Repository
class CopilotKtormRepository(
    database: Database,
) : KtormRepository<CopilotEntity, Copilots>(database, Copilots) {

    fun findAllByDeleteIsFalse(pageable: Pageable): Page<CopilotEntity> {
        val sequence = entities.filter { it.delete eq false }
        return sequence.paginate(pageable)
    }

    fun findByCopilotIdAndDeleteIsFalse(copilotId: Long): CopilotEntity? {
        return entities.firstOrNull {
            it.copilotId eq copilotId and (it.delete eq false)
        }
    }

    fun findByCopilotIdInAndDeleteIsFalse(copilotIds: Collection<Long>): List<CopilotEntity> {
        return entities.filter {
            it.copilotId inList copilotIds and (it.delete eq false)
        }.toList()
    }

    fun findByCopilotId(copilotId: Long): CopilotEntity? {
        return entities.firstOrNull { it.copilotId eq copilotId }
    }

    fun existsCopilotsByCopilotId(copilotId: Long): Boolean {
        return entities.any { it.copilotId eq copilotId }
    }

    fun existsByCopilotId(copilotId: Long): Boolean {
        return entities.any { it.copilotId eq copilotId }
    }

    fun findByContentIsNotNull(): Stream<CopilotEntity> {
        return entities.filter {
            it.content notEq ""
        }.toList().stream()
    }

    fun insert(copilot: CopilotEntity): CopilotEntity {
        entities.add(copilot)
        return copilot
    }

    fun insertEntity(copilot: CopilotEntity): CopilotEntity {
        entities.add(copilot)
        return copilot
    }

    fun updateEntity(copilot: CopilotEntity): CopilotEntity {
        copilot.flushChanges()
        return copilot
    }

    override fun findById(id: Any): CopilotEntity? {
        return entities.firstOrNull { it.copilotId eq id.toString().toLong() }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.copilotId eq id.toString().toLong() } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.copilotId eq id.toString().toLong() }
    }

    override fun getIdColumn(entity: CopilotEntity): Any = entity.copilotId

    override fun isNewEntity(entity: CopilotEntity): Boolean {
        // 如果copilotId为0或在数据库中不存在，则认为是新实体
        return entity.copilotId == 0L || !existsById(entity.copilotId)
    }

    fun findAllByUploadTimeAfterOrDeleteTimeAfter(uploadTimeAfter: LocalDateTime, deleteTimeAfter: LocalDateTime): List<CopilotEntity> {
        return entities.filter {
            (it.uploadTime gte uploadTimeAfter) or (it.deleteTime gte deleteTimeAfter)
        }.toList()
    }
}
