package plus.maa.backend.repository.entity

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.jackson.json
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.datetime
import org.ktorm.schema.enum
import org.ktorm.schema.long
import org.ktorm.schema.text
import org.ktorm.schema.varchar
import org.springframework.util.Assert
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

interface CopilotSetEntity : Entity<CopilotSetEntity> {
    var id: Long
    var name: String
    var description: String
    var copilotIds: List<Long> // JSON格式存储作业ID列表
    var creatorId: Long
    var createTime: LocalDateTime
    var updateTime: LocalDateTime
    var status: CopilotSetStatus
    var delete: Boolean

    companion object : Entity.Factory<CopilotSetEntity>()
}

object CopilotSets : Table<CopilotSetEntity>("copilot_set") {
    val id = long("id").primaryKey().bindTo { it.id }
    val name = varchar("name").bindTo { it.name }
    val description = text("description").bindTo { it.description }
    val copilotIds = json<List<Long>>("copilot_ids", objectMapper).bindTo { it.copilotIds }
    val creatorId = long("creator_id").bindTo { it.creatorId }
    val createTime = datetime("create_time").bindTo { it.createTime }
    val updateTime = datetime("update_time").bindTo { it.updateTime }
    val status = enum<CopilotSetStatus>("status").bindTo { it.status }
    val delete = boolean("delete").bindTo { it.delete }
}

val Database.copilotSets get() = sequenceOf(CopilotSets)

private val objectMapper = jacksonObjectMapper()

/**
 * 设置作业ID列表（序列化为JSON字符串）
 */
fun CopilotSetEntity.setCopilotIdsWithCheck(ids: Collection<Long>) {
    val result = when {
        ids.isEmpty() || ids.size == 1 -> ids
        else -> {
            val distinctIds = LinkedHashSet(ids)
            Assert.state(distinctIds.size <= 1000, "作业集总作业数量不能超过1000条")
            distinctIds
        }
    }

    copilotIds = result.toList()
}
