package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.eq
import org.ktorm.dsl.inList
import org.ktorm.dsl.like
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.ktorm.entity.toList
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.common.extensions.paginate
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.Users
import java.util.stream.Stream

@Repository
class UserKtormRepository(
    database: Database,
) : KtormRepository<UserEntity, Users>(database, Users) {

    fun findByEmail(email: String): UserEntity? {
        return entities.firstOrNull { it.email eq email }
    }

    fun findByUserId(userId: String): UserEntity? {
        return entities.firstOrNull { it.userId eq userId }
    }

    fun searchUsers(userName: String, pageable: Pageable): Page<MaaUserInfo> {
        val sequence = entities.filter {
            it.userName like "%$userName%" and (it.status eq 1)
        }

        val page = sequence.paginate(pageable)

        // Convert UserEntity to MaaUserInfo
        val userInfos = page.content.map { user ->
            MaaUserInfo(
                id = user.userId,
                userName = user.userName,
            )
        }

        return PageImpl(userInfos, pageable, page.totalElements)
    }

    fun existsByUserName(userName: String): Boolean {
        return entities.any { it.userName eq userName }
    }

    fun findAllBy(): Stream<UserEntity> {
        return entities.toList().stream()
    }

    fun findAllById(ids: Iterable<String>): List<UserEntity> {
        val idList = ids.toList()
        if (idList.isEmpty()) {
            return mutableListOf()
        }
        return entities.filter { it.userId inList idList }.toList()
    }

    override fun getIdColumn(entity: UserEntity): Any = entity.userId

    override fun findById(id: Any): UserEntity? {
        return entities.firstOrNull { it.userId eq id.toString() }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.userId eq id.toString() } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.userId eq id.toString() }
    }

    override fun isNewEntity(entity: UserEntity): Boolean {
        // 如果userId为空或在数据库中不存在，则认为是新实体
        return entity.userId.isBlank() || !existsById(entity.userId)
    }

    /**
     * 从MaaUser创建UserEntity
     */
    fun createFromMaaUser(maaUser: MaaUser): UserEntity {
        return UserEntity {
            this.userId = maaUser.userId ?: ""
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
