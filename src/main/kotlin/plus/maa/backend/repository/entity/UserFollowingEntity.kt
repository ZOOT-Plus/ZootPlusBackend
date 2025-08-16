package plus.maa.backend.repository.entity

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.datetime
import org.ktorm.schema.text
import org.ktorm.schema.varchar
import java.time.LocalDateTime

interface UserFollowingEntity : Entity<UserFollowingEntity> {
    var id: String
    var userId: String
    var followingIds: String // JSON格式存储关注列表
    var updatedAt: LocalDateTime

    companion object : Entity.Factory<UserFollowingEntity>()
}

object UserFollowings : Table<UserFollowingEntity>("user_following") {
    val id = varchar("id").primaryKey().bindTo { it.id }
    val userId = varchar("user_id").bindTo { it.userId }
    val followingIds = text("following_ids").bindTo { it.followingIds }
    val updatedAt = datetime("updated_at").bindTo { it.updatedAt }
}

val Database.userFollowings get() = sequenceOf(UserFollowings)

// 扩展方法用于处理关注列表
private val objectMapper = jacksonObjectMapper()

/**
 * 获取关注列表（从JSON字符串解析）
 */
val UserFollowingEntity.followList: MutableList<String>
    get() = try {
        if (followingIds.isBlank()) {
            mutableListOf()
        } else {
            objectMapper.readValue<MutableList<String>>(followingIds)
        }
    } catch (e: Exception) {
        mutableListOf()
    }

/**
 * 设置关注列表（序列化为JSON字符串）
 */
fun UserFollowingEntity.setFollowList(list: List<String>) {
    followingIds = objectMapper.writeValueAsString(list)
}
