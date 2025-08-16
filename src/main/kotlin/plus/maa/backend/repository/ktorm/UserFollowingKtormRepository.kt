package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.UserFollowingEntity
import plus.maa.backend.repository.entity.UserFollowings
import plus.maa.backend.repository.entity.followList

@Repository
class UserFollowingKtormRepository(
    database: Database,
) : KtormRepository<UserFollowingEntity, UserFollowings>(database, UserFollowings) {

    /**
     * 获取用户关注列表实体
     */
    fun findByUserId(userId: String): UserFollowingEntity? {
        return entities.firstOrNull { it.userId eq userId }
    }

    /**
     * 获取用户关注的ID列表
     */
    fun getFollowingIds(userId: String): List<String> {
        return findByUserId(userId)?.followList ?: emptyList()
    }

    fun insertEntity(entity: UserFollowingEntity): UserFollowingEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: UserFollowingEntity): UserFollowingEntity {
        entity.flushChanges()
        return entity
    }

    override fun findById(id: Any): UserFollowingEntity? {
        return entities.firstOrNull { it.id eq id.toString() }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq id.toString() } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq id.toString() }
    }

    override fun getIdColumn(entity: UserFollowingEntity): Any = entity.id

    override fun isNewEntity(entity: UserFollowingEntity): Boolean {
        return entity.id.isBlank() || !existsById(entity.id)
    }

    override fun save(entity: UserFollowingEntity): UserFollowingEntity {
        return if (isNewEntity(entity)) {
            insertEntity(entity)
        } else {
            updateEntity(entity)
        }
    }
}
