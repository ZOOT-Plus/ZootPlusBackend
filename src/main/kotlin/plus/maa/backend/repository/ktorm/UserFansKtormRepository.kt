package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.UserFansEntity
import plus.maa.backend.repository.entity.UserFansTable

@Repository
class UserFansKtormRepository(
    database: Database,
) : KtormRepository<UserFansEntity, UserFansTable>(database, UserFansTable) {

    /**
     * 获取用户粉丝列表实体
     */
    fun findByUserId(userId: String): UserFansEntity? {
        return entities.firstOrNull { it.userId eq userId }
    }

    fun insertEntity(entity: UserFansEntity): UserFansEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: UserFansEntity): UserFansEntity {
        entity.flushChanges()
        return entity
    }

    override fun findById(id: Any): UserFansEntity? {
        return entities.firstOrNull { it.id eq id.toString() }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq id.toString() } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq id.toString() }
    }

    override fun getIdColumn(entity: UserFansEntity): Any = entity.id

    override fun isNewEntity(entity: UserFansEntity): Boolean {
        return entity.id.isBlank() || !existsById(entity.id)
    }

    override fun save(entity: UserFansEntity): UserFansEntity {
        return if (isNewEntity(entity)) {
            insertEntity(entity)
        } else {
            updateEntity(entity)
        }
    }
}
