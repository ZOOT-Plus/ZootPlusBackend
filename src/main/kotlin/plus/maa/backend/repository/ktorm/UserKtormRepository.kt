package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.delete
import org.ktorm.dsl.eq
import org.ktorm.dsl.from
import org.ktorm.dsl.inList
import org.ktorm.dsl.insert
import org.ktorm.dsl.limit
import org.ktorm.dsl.map
import org.ktorm.dsl.minus
import org.ktorm.dsl.plus
import org.ktorm.dsl.select
import org.ktorm.dsl.update
import org.ktorm.dsl.where
import org.ktorm.entity.EntitySequence
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.toList
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.UserFollows
import plus.maa.backend.repository.entity.Users
import java.time.LocalDateTime

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

    @Transactional
    fun follow(userId: Long, followUserId: Long) {
        val r = database.from(UserFollows).select(UserFollows.userId)
            .where { (UserFollows.userId eq userId) and (UserFollows.followUserId eq followUserId) }
            .limit(1)
            .map { it[UserFollows.userId] }
        if (r.isNotEmpty()) {
            return
        }
        database.insert(UserFollows) {
            set(it.userId, userId)
            set(it.followUserId, followUserId)
            set(it.updatedAt, LocalDateTime.now())
        }
        database.update(Users) {
            set(it.followingCount, it.followingCount + 1)
            where {
                it.userId eq userId
            }
        }
        database.update(Users) {
            set(it.fansCount, it.fansCount + 1)
            where {
                it.userId eq followUserId
            }
        }
    }

    @Transactional
    fun unfollow(userId: Long, followUserId: Long) {
        val r = database.from(UserFollows).select(UserFollows.userId)
            .where { (UserFollows.userId eq userId) and (UserFollows.followUserId eq followUserId) }
            .limit(1)
            .map { it[UserFollows.userId] }
        if (r.isEmpty()) {
            return
        }
        database.delete(UserFollows) {
            (it.userId eq userId) and (it.followUserId eq followUserId)
        }
        database.update(Users) {
            set(it.followingCount, it.followingCount - 1)
            where {
                it.userId eq userId
            }
        }
        database.update(Users) {
            set(it.fansCount, it.fansCount - 1)
            where {
                it.userId eq followUserId
            }
        }
    }

    fun follows(userId: Long): EntitySequence<UserEntity, Users> {
        return entities.filter {
            it.userId inList
                database.from(UserFollows).select(UserFollows.followUserId)
                    .where { UserFollows.userId eq userId }
        }
    }

    fun fans(userId: Long): EntitySequence<UserEntity, Users> {
        return entities.filter {
            it.userId inList
                database.from(UserFollows).select(UserFollows.userId)
                    .where { UserFollows.followUserId eq userId }
        }
    }

    override fun save(entity: UserEntity): UserEntity {
        return if (isNewEntity(entity)) {
            insertEntity(entity)
        } else {
            updateEntity(entity)
        }
    }
}
