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

interface UserFansEntity : Entity<UserFansEntity> {
    var id: String
    var userId: String
    var fansIds: String // JSON格式存储粉丝列表
    var updatedAt: LocalDateTime

    companion object : Entity.Factory<UserFansEntity>()
}

object UserFansTable : Table<UserFansEntity>("user_fans") {
    val id = varchar("id").primaryKey().bindTo { it.id }
    val userId = varchar("user_id").bindTo { it.userId }
    val fansIds = text("fans_ids").bindTo { it.fansIds }
    val updatedAt = datetime("updated_at").bindTo { it.updatedAt }
}

val Database.userFans get() = sequenceOf(UserFansTable)

// 扩展方法用于处理粉丝列表
private val objectMapper = jacksonObjectMapper()

/**
 * 获取粉丝列表（从JSON字符串解析）
 */
val UserFansEntity.fansList: MutableList<String>
    get() = try {
        if (fansIds.isBlank()) {
            mutableListOf()
        } else {
            objectMapper.readValue<MutableList<String>>(fansIds)
        }
    } catch (e: Exception) {
        mutableListOf()
    }

/**
 * 设置粉丝列表（序列化为JSON字符串）
 */
fun UserFansEntity.setFansList(list: List<String>) {
    fansIds = objectMapper.writeValueAsString(list)
}
