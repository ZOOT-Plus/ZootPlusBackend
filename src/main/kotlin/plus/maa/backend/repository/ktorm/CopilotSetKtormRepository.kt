package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.repository.entity.CopilotSets

@Repository
class CopilotSetKtormRepository(
    database: Database,
) : KtormRepository<CopilotSetEntity, CopilotSets>(database, CopilotSets) {

    override fun findById(id: Any): CopilotSetEntity? {
        return entities.firstOrNull { it.id eq (id as Long) }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq (id as Long) } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq (id as Long) }
    }

    override fun getIdColumn(entity: CopilotSetEntity): Any = entity.id

    override fun isNewEntity(entity: CopilotSetEntity): Boolean {
        return entity.id == 0L || !existsById(entity.id)
    }

    fun insertEntity(entity: CopilotSetEntity): CopilotSetEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: CopilotSetEntity): CopilotSetEntity {
        entity.flushChanges()
        return entity
    }

    fun findByIdAsOptional(id: Long): java.util.Optional<CopilotSetEntity> {
        return findById(id)?.let { java.util.Optional.of(it) } ?: java.util.Optional.empty()
    }

    override fun save(entity: CopilotSetEntity): CopilotSetEntity {
        return if (isNewEntity(entity)) {
            insertEntity(entity)
        } else {
            updateEntity(entity)
        }
    }
}
