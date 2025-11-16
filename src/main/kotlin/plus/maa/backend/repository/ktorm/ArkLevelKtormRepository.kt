package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.dsl.from
import org.ktorm.dsl.inList
import org.ktorm.dsl.like
import org.ktorm.dsl.map
import org.ktorm.dsl.or
import org.ktorm.dsl.select
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.ArkLevels

@Repository
class ArkLevelKtormRepository(
    database: Database,
) : KtormRepository<ArkLevelEntity, ArkLevels>(database, ArkLevels) {

    fun findByStageId(stageId: String): ArkLevelEntity? {
        return entities.firstOrNull { it.stageId eq stageId }
    }

    fun findAllByStageIds(stageIds: List<String>): List<ArkLevelEntity> {
        return entities.filter { it.stageId inList stageIds }.toList()
    }

    fun findByLevelId(levelId: String): ArkLevelEntity? {
        return entities.firstOrNull { it.levelId eq levelId }
    }

    fun findAllOpenLevels(): List<ArkLevelEntity> {
        return entities.filter { it.isOpen eq true }.toList()
    }

    fun insertEntity(entity: ArkLevelEntity): ArkLevelEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: ArkLevelEntity): ArkLevelEntity {
        entity.flushChanges()
        return entity
    }

    override fun findById(id: Any): ArkLevelEntity? {
        return entities.firstOrNull { it.id eq (id as Long) }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq (id as Long) } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq (id as Long) }
    }

    override fun getIdColumn(entity: ArkLevelEntity): Any = entity.id

    override fun isNewEntity(entity: ArkLevelEntity): Boolean {
        return entity.id == 0L || !existsById(entity.id)
    }

    override fun save(entity: ArkLevelEntity): ArkLevelEntity {
        return if (isNewEntity(entity)) {
            insertEntity(entity)
        } else {
            updateEntity(entity)
        }
    }

    fun findByLevelIdFuzzy(levelId: String): List<ArkLevelEntity> {
        return entities.filter { it.levelId like "%$levelId%" }.toList()
    }

    fun queryLevelByKeyword(keyword: String): List<ArkLevelEntity> {
        return entities.filter {
            it.name like "%$keyword%" or
                (it.levelId like "%$keyword%") or
                (it.stageId like "%$keyword%")
        }.toList()
    }

    fun findAllShaBy(): List<ShaProjection> {
        return database.from(ArkLevels)
            .select(ArkLevels.sha)
            .map { row -> ShaProjection(row[ArkLevels.sha]!!) }
    }

    fun findAllByCatOne(catOne: String, pageable: Pageable): Page<ArkLevelEntity> {
        val total = entities.filter { it.catOne eq catOne }.count()
        val items = entities.filter { it.catOne eq catOne }
            .drop(pageable.offset.toInt())
            .take(pageable.pageSize)
            .toList()
        return PageImpl(items, pageable, total.toLong())
    }

    fun saveAll(entities: List<ArkLevelEntity>) {
        entities.forEach { save(it) }
    }

    data class ShaProjection(val sha: String)
}
