package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.dsl.inList
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.toList
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.Users

@Repository
class UserKtormRepository(
    database: Database,
) : KtormRepository<UserEntity, Users>(database, Users) {

    fun findByEmail(email: String): UserEntity? {
        return entities.firstOrNull { it.email eq email }
    }

    fun existsByUserName(userName: String): Boolean {
        return entities.any { it.userName eq userName }
    }

    fun findAllById(ids: Iterable<Long>): List<UserEntity> {
        val idList = ids.toList()
        if (idList.isEmpty()) {
            return mutableListOf()
        }
        return entities.filter { it.userId inList idList }.toList()
    }

    override fun getIdColumn(entity: UserEntity): Any = entity.userId

    override fun findById(id: Any): UserEntity? {
        return entities.firstOrNull { it.userId eq (id as Long) }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.userId eq (id as Long) } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.userId eq (id as Long) }
    }

    override fun isNewEntity(entity: UserEntity): Boolean {
        // 如果userId为0或在数据库中不存在，则认为是新实体
        return entity.userId == 0L || !existsById(entity.userId)
    }

    /**
     * 从MaaUser创建UserEntity
     */
    fun createFromMaaUser(maaUser: MaaUser): UserEntity {
        return UserEntity {
            this.userName = maaUser.userName
            this.email = maaUser.email
            this.password = maaUser.password
            this.status = maaUser.status
            this.pwdUpdateTime = maaUser.pwdUpdateTime
            this.followingCount = maaUser.followingCount
            this.fansCount = maaUser.fansCount
        }
    }

    fun insertEntity(entity: UserEntity): UserEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: UserEntity): UserEntity {
        entity.flushChanges()
        return entity
    }

    override fun save(entity: UserEntity): UserEntity {
        return if (isNewEntity(entity)) {
            insertEntity(entity)
        } else {
            updateEntity(entity)
        }
    }
}
