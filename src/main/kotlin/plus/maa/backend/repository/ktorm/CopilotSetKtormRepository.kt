package plus.maa.backend.repository.ktorm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.dsl.like
import org.ktorm.dsl.or
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.common.extensions.paginate
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.repository.entity.CopilotSets

@Repository
class CopilotSetKtormRepository(
    database: Database,
    private val objectMapper: ObjectMapper,
) : KtormRepository<CopilotSetEntity, CopilotSets>(database, CopilotSets) {

    fun findByKeyword(keyword: String, pageable: Pageable): Page<CopilotSetEntity> {
        val sequence = entities.filter {
            (it.name like "%$keyword%") or (it.description like "%$keyword%")
        }
        return sequence.paginate(pageable)
    }

    fun findByIdAndDeleteIsFalse(id: Long): CopilotSetEntity? {
        return entities.firstOrNull {
            (it.id eq id) and (it.delete eq false)
        }
    }

    fun findAllByDeleteIsFalse(pageable: Pageable): Page<CopilotSetEntity> {
        val sequence = entities.filter { it.delete eq false }
        return sequence.paginate(pageable)
    }

    fun findByCreatorIdAndDeleteIsFalse(creatorId: String, pageable: Pageable): Page<CopilotSetEntity> {
        val sequence = entities.filter {
            (it.creatorId eq creatorId) and (it.delete eq false)
        }
        return sequence.paginate(pageable)
    }

    /**
     * 将JSON格式的copilotIds转换为List
     */
    fun getCopilotIdsList(entity: CopilotSetEntity): MutableList<Long> {
        return try {
            objectMapper.readValue<MutableList<Long>>(entity.copilotIds)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /**
     * 将List转换为JSON格式保存
     */
    fun setCopilotIdsList(entity: CopilotSetEntity, copilotIds: List<Long>) {
        entity.copilotIds = objectMapper.writeValueAsString(copilotIds)
    }

    override fun findById(id: Any): CopilotSetEntity? {
        return entities.firstOrNull { it.id eq id.toString().toLong() }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq id.toString().toLong() } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq id.toString().toLong() }
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
