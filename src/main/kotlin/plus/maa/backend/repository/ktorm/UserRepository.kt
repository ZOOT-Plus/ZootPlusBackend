package plus.maa.backend.repository.ktorm

import org.jdbi.v3.core.Jdbi
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.UserFollowEntity
import java.time.LocalDateTime

/**
 * 用户模块 repository。
 *
 * - insert 用 executeAndReturnGeneratedKeys 回填自增 id；`userId == 0` 时省略 user_id 列（自增），
 *   否则显式携带
 * - follows/fans 返回 Spring Data [Page]，分页（COUNT + LIMIT/OFFSET）内联在本类。
 */
@Repository
class UserRepository(
    private val jdbi: Jdbi,
) {
    // ------------------------------------------------------------------
    // user 表基础 CRUD
    // ------------------------------------------------------------------

    fun findByEmail(email: String): UserEntity? {
        return jdbi.withHandle<UserEntity?, Exception> { handle ->
            handle.createQuery("SELECT * FROM \"user\" WHERE email = :email LIMIT 1")
                .bind("email", email)
                .mapTo(UserEntity::class.java)
                .findFirst()
                .orElse(null)
        }
    }

    fun existsByUserName(userName: String): Boolean {
        return jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT 1 FROM \"user\" WHERE user_name = :userName LIMIT 1")
                .bind("userName", userName)
                .mapTo(Int::class.java)
                .findFirst()
                .isPresent
        }
    }

    fun findAllById(ids: Iterable<Long>): List<UserEntity> {
        val idList = ids.toList()
        if (idList.isEmpty()) {
            return mutableListOf()
        }
        return jdbi.withHandle<List<UserEntity>, Exception> { handle ->
            handle.createQuery("SELECT * FROM \"user\" WHERE user_id IN (<ids>)")
                .bindList("ids", idList)
                .mapTo(UserEntity::class.java)
                .list()
        }
    }

    fun findById(id: Any): UserEntity? {
        return jdbi.withHandle<UserEntity?, Exception> { handle ->
            handle.createQuery("SELECT * FROM \"user\" WHERE user_id = :id")
                .bind("id", id as Long)
                .mapTo(UserEntity::class.java)
                .findFirst()
                .orElse(null)
        }
    }

    fun deleteById(id: Any): Boolean {
        return jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM \"user\" WHERE user_id = :id")
                .bind("id", id as Long)
                .execute()
        } > 0
    }

    fun existsById(id: Any): Boolean {
        return jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT 1 FROM \"user\" WHERE user_id = :id LIMIT 1")
                .bind("id", id as Long)
                .mapTo(Int::class.java)
                .findFirst()
                .isPresent
        }
    }

    fun findAll(): List<UserEntity> {
        return jdbi.withHandle<List<UserEntity>, Exception> { handle ->
            handle.createQuery("SELECT * FROM \"user\"")
                .mapTo(UserEntity::class.java)
                .list()
        }
    }

    fun count(): Long {
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM \"user\"")
                .mapTo(Long::class.java)
                .one()
        }
    }

    fun isNewEntity(entity: UserEntity): Boolean {
        // userId 为 0 或数据库中不存在，则认为是新实体（与基线一致）
        return entity.userId == 0L || !existsById(entity.userId)
    }

    /**
     * 从 MaaUser 创建 UserEntity（userId 保持 0，视为新实体）
     */
    fun createFromMaaUser(maaUser: MaaUser): UserEntity {
        return UserEntity(
            userName = maaUser.userName,
            email = maaUser.email,
            password = maaUser.password,
            status = maaUser.status,
            pwdUpdateTime = maaUser.pwdUpdateTime,
            followingCount = maaUser.followingCount,
            fansCount = maaUser.fansCount,
        )
    }

    /**
     * 插入并回填自增 user_id；userId != 0 时按基线行为显式携带 user_id 插入。
     * 回填发生在传入实体对象上（原地修改，服务层依赖此行为，
     * 如 UserService.register 在 save 后直接读 entity.userId）。
     */
    fun insertEntity(entity: UserEntity): UserEntity {
        val newId = jdbi.withHandle<Long, Exception> { handle ->
            if (entity.userId == 0L) {
                handle.createUpdate(
                    """
                    INSERT INTO "user" (user_name, email, password, status, pwd_update_time, following_count, fans_count)
                    VALUES (:userName, :email, :password, :status, :pwdUpdateTime, :followingCount, :fansCount)
                    """.trimIndent(),
                )
                    .bind("userName", entity.userName)
                    .bind("email", entity.email)
                    .bind("password", entity.password)
                    .bind("status", entity.status)
                    .bind("pwdUpdateTime", entity.pwdUpdateTime)
                    .bind("followingCount", entity.followingCount)
                    .bind("fansCount", entity.fansCount)
                    .executeAndReturnGeneratedKeys("user_id")
                    .mapTo(Long::class.java)
                    .one()
            } else {
                handle.createUpdate(
                    """
                    INSERT INTO "user" (user_id, user_name, email, password, status, pwd_update_time, following_count, fans_count)
                    VALUES (:userId, :userName, :email, :password, :status, :pwdUpdateTime, :followingCount, :fansCount)
                    """.trimIndent(),
                )
                    .bind("userId", entity.userId)
                    .bind("userName", entity.userName)
                    .bind("email", entity.email)
                    .bind("password", entity.password)
                    .bind("status", entity.status)
                    .bind("pwdUpdateTime", entity.pwdUpdateTime)
                    .bind("followingCount", entity.followingCount)
                    .bind("fansCount", entity.fansCount)
                    .execute()
                entity.userId
            }
        }
        entity.userId = newId
        return entity
    }

    /**
     * 全列 UPDATE（本项目更新路径均为「读出全字段再改」，未改列写入原值，行为等价）。
     */
    fun updateEntity(entity: UserEntity): UserEntity {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE "user" SET user_name = :userName, email = :email, password = :password, status = :status,
                    pwd_update_time = :pwdUpdateTime, following_count = :followingCount, fans_count = :fansCount
                WHERE user_id = :userId
                """.trimIndent(),
            )
                .bind("userId", entity.userId)
                .bind("userName", entity.userName)
                .bind("email", entity.email)
                .bind("password", entity.password)
                .bind("status", entity.status)
                .bind("pwdUpdateTime", entity.pwdUpdateTime)
                .bind("followingCount", entity.followingCount)
                .bind("fansCount", entity.fansCount)
                .execute()
        }
        return entity
    }

    fun save(entity: UserEntity): UserEntity {
        return if (isNewEntity(entity)) {
            insertEntity(entity)
        } else {
            updateEntity(entity)
        }
    }

    // ------------------------------------------------------------------
    // follow / unfollow（多语句事务）
    // ------------------------------------------------------------------

    fun follow(userId: Long, followUserId: Long) {
        jdbi.useTransaction<Exception> { handle ->
            val exists = handle.createQuery(
                "SELECT user_id FROM user_follow WHERE user_id = :userId AND follow_user_id = :followUserId LIMIT 1",
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .mapTo(Long::class.java)
                .findFirst()
                .isPresent
            if (exists) {
                return@useTransaction
            }
            handle.createUpdate(
                """
                INSERT INTO user_follow (user_id, follow_user_id, special_follow, updated_at)
                VALUES (:userId, :followUserId, false, :updatedAt)
                """.trimIndent(),
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .bind("updatedAt", LocalDateTime.now())
                .execute()
            handle.createUpdate(
                "UPDATE \"user\" SET following_count = following_count + 1 WHERE user_id = :userId",
            )
                .bind("userId", userId)
                .execute()
            handle.createUpdate(
                "UPDATE \"user\" SET fans_count = fans_count + 1 WHERE user_id = :userId",
            )
                .bind("userId", followUserId)
                .execute()
        }
    }

    fun unfollow(userId: Long, followUserId: Long) {
        jdbi.useTransaction<Exception> { handle ->
            val exists = handle.createQuery(
                "SELECT user_id FROM user_follow WHERE user_id = :userId AND follow_user_id = :followUserId LIMIT 1",
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .mapTo(Long::class.java)
                .findFirst()
                .isPresent
            if (!exists) {
                return@useTransaction
            }
            handle.createUpdate(
                "DELETE FROM user_follow WHERE user_id = :userId AND follow_user_id = :followUserId",
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .execute()
            handle.createUpdate(
                "UPDATE \"user\" SET following_count = following_count - 1 WHERE user_id = :userId",
            )
                .bind("userId", userId)
                .execute()
            handle.createUpdate(
                "UPDATE \"user\" SET fans_count = fans_count - 1 WHERE user_id = :userId",
            )
                .bind("userId", followUserId)
                .execute()
        }
    }

    /**
     * 关注列表（IN 子查询）。返回 Spring Data [Page]：
     * 分页逻辑（COUNT + LIMIT/OFFSET）内联在本方法；ORDER BY user_id 保证跨页稳定。
     */
    fun follows(userId: Long, pageable: Pageable): Page<UserEntity> {
        val whereSql = "SELECT * FROM \"user\" WHERE user_id IN (SELECT follow_user_id FROM user_follow WHERE user_id = :userId)"
        val total = jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(
                "SELECT COUNT(*) FROM \"user\" WHERE user_id IN (SELECT follow_user_id FROM user_follow WHERE user_id = :userId)",
            )
                .bind("userId", userId)
                .mapTo(Long::class.java)
                .one()
        }
        val data = jdbi.withHandle<List<UserEntity>, Exception> { handle ->
            handle.createQuery("$whereSql ORDER BY user_id LIMIT :limit OFFSET :offset")
                .bind("userId", userId)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .mapTo(UserEntity::class.java)
                .list()
        }
        return PageImpl(data, pageable, total)
    }

    /**
     * 粉丝列表（IN 子查询），返回 Spring Data [Page]；ORDER BY user_id 保证跨页稳定。
     */
    fun fans(userId: Long, pageable: Pageable): Page<UserEntity> {
        val whereSql = "SELECT * FROM \"user\" WHERE user_id IN (SELECT user_id FROM user_follow WHERE follow_user_id = :userId)"
        val total = jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(
                "SELECT COUNT(*) FROM \"user\" WHERE user_id IN (SELECT user_id FROM user_follow WHERE follow_user_id = :userId)",
            )
                .bind("userId", userId)
                .mapTo(Long::class.java)
                .one()
        }
        val data = jdbi.withHandle<List<UserEntity>, Exception> { handle ->
            handle.createQuery("$whereSql ORDER BY user_id LIMIT :limit OFFSET :offset")
                .bind("userId", userId)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .mapTo(UserEntity::class.java)
                .list()
        }
        return PageImpl(data, pageable, total)
    }

    // ------------------------------------------------------------------
    // user_follow 复合主键查询与映射
    // ------------------------------------------------------------------

    /**
     * 查询 userId 关注了 targetIds 中的哪些用户，返回 followUserId -> updatedAt 的映射
     */
    fun getFollowUpdatedAtMap(userId: Long, targetIds: List<Long>): Map<Long, LocalDateTime> {
        if (targetIds.isEmpty()) return emptyMap()
        return jdbi.withHandle<Map<Long, LocalDateTime>, Exception> { handle ->
            handle.createQuery(
                "SELECT follow_user_id, updated_at FROM user_follow WHERE user_id = :userId AND follow_user_id IN (<targetIds>)",
            )
                .bind("userId", userId)
                .bindList("targetIds", targetIds)
                .map { rs, _ ->
                    rs.getObject("follow_user_id", Long::class.javaObjectType)!! to
                        rs.getObject("updated_at", LocalDateTime::class.java)
                }
                .list()
                .toMap()
        }
    }

    fun findFollow(userId: Long, followUserId: Long): UserFollowEntity? {
        return jdbi.withHandle<UserFollowEntity?, Exception> { handle ->
            handle.createQuery(
                "SELECT * FROM user_follow WHERE user_id = :userId AND follow_user_id = :followUserId",
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .mapTo(UserFollowEntity::class.java)
                .findFirst()
                .orElse(null)
        }
    }

    fun setSpecialFollow(userId: Long, followUserId: Long, status: Boolean): Boolean {
        return jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                "UPDATE user_follow SET special_follow = :status WHERE user_id = :userId AND follow_user_id = :followUserId",
            )
                .bind("status", status)
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .execute()
        } > 0
    }

    fun getSpecialFollowedTargetIds(userId: Long, targetIds: List<Long>): Set<Long> {
        if (targetIds.isEmpty()) return emptySet()
        return jdbi.withHandle<Set<Long>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT follow_user_id FROM user_follow
                WHERE user_id = :userId AND follow_user_id IN (<targetIds>) AND special_follow = TRUE
                """.trimIndent(),
            )
                .bind("userId", userId)
                .bindList("targetIds", targetIds)
                .mapTo(Long::class.java)
                .list()
                .toSet()
        }
    }

    fun getSpecialFollowerIds(followUserId: Long): List<Long> {
        return jdbi.withHandle<List<Long>, Exception> { handle ->
            handle.createQuery(
                "SELECT user_id FROM user_follow WHERE follow_user_id = :followUserId AND special_follow = TRUE",
            )
                .bind("followUserId", followUserId)
                .mapTo(Long::class.java)
                .list()
        }
    }

    /**
     * 查询 fanIds 中谁关注了 userId，返回 fanId -> updatedAt 的映射
     */
    fun getFansUpdatedAtMap(fanIds: List<Long>, userId: Long): Map<Long, LocalDateTime> {
        if (fanIds.isEmpty()) return emptyMap()
        return jdbi.withHandle<Map<Long, LocalDateTime>, Exception> { handle ->
            handle.createQuery(
                "SELECT user_id, updated_at FROM user_follow WHERE user_id IN (<fanIds>) AND follow_user_id = :userId",
            )
                .bindList("fanIds", fanIds)
                .bind("userId", userId)
                .map { rs, _ ->
                    rs.getObject("user_id", Long::class.javaObjectType)!! to
                        rs.getObject("updated_at", LocalDateTime::class.java)
                }
                .list()
                .toMap()
        }
    }

    /**
     * 查询 userId 关注了 targetIds 中的哪些用户，返回被关注的 targetIds 子集
     */
    fun getFollowedTargetIds(userId: Long, targetIds: List<Long>): Set<Long> {
        if (targetIds.isEmpty()) return emptySet()
        return jdbi.withHandle<Set<Long>, Exception> { handle ->
            handle.createQuery(
                "SELECT follow_user_id FROM user_follow WHERE user_id = :userId AND follow_user_id IN (<targetIds>)",
            )
                .bind("userId", userId)
                .bindList("targetIds", targetIds)
                .mapTo(Long::class.java)
                .list()
                .toSet()
        }
    }

    /**
     * 查询 targetIds 中哪些用户关注了 userId，返回关注了 userId 的 targetIds 子集
     */
    fun getFollowerTargetIds(targetIds: List<Long>, userId: Long): Set<Long> {
        if (targetIds.isEmpty()) return emptySet()
        return jdbi.withHandle<Set<Long>, Exception> { handle ->
            handle.createQuery(
                "SELECT user_id FROM user_follow WHERE user_id IN (<targetIds>) AND follow_user_id = :userId",
            )
                .bindList("targetIds", targetIds)
                .bind("userId", userId)
                .mapTo(Long::class.java)
                .list()
                .toSet()
        }
    }

    fun isFollowing(userId: Long, followUserId: Long): Boolean {
        return jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                "SELECT user_id FROM user_follow WHERE user_id = :userId AND follow_user_id = :followUserId LIMIT 1",
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .mapTo(Long::class.java)
                .findFirst()
                .isPresent
        }
    }

    fun isSpecialFollowing(userId: Long, followUserId: Long): Boolean {
        return jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                SELECT user_id FROM user_follow
                WHERE user_id = :userId AND follow_user_id = :followUserId AND special_follow = TRUE
                LIMIT 1
                """.trimIndent(),
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .mapTo(Long::class.java)
                .findFirst()
                .isPresent
        }
    }

    // ------------------------------------------------------------------
    // 原生查询点
    // ------------------------------------------------------------------

    /** 按 id 查用户或 null（服务层负责 `?: UserEntity.UNKNOWN` 兜底）。 */
    fun findByIdOrNull(id: Long): UserEntity? = findById(id)

    /** 用户名模糊搜索（LIKE 通配符转义，搜索词中的 %/_ 按字面匹配），粉丝数降序 + 分页。 */
    fun searchByUserName(userName: String, offset: Int, limit: Int): List<UserEntity> {
        val pattern = "%" + escapeLike(userName) + "%"
        return jdbi.withHandle<List<UserEntity>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT * FROM "user" WHERE user_name LIKE :userName ESCAPE '\'
                ORDER BY fans_count DESC LIMIT :limit OFFSET :offset
                """.trimIndent(),
            )
                .bind("userName", pattern)
                .bind("limit", limit)
                .bind("offset", offset)
                .mapTo(UserEntity::class.java)
                .list()
        }
    }

    /**
     * 转义 LIKE 模式串中的元字符（\、%、_），使搜索词按字面匹配。
     * 配合 LIKE ... ESCAPE '\' 使用。
     */
    private fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
