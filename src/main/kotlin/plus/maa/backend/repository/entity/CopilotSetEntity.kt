package plus.maa.backend.repository.entity

import kotlinx.serialization.encodeToString
import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.SqlType
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.datetime
import org.ktorm.schema.double
import org.ktorm.schema.enum
import org.ktorm.schema.long
import org.ktorm.schema.text
import org.ktorm.schema.varchar
import org.postgresql.util.PGobject
import org.springframework.util.Assert
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.service.model.CopilotSetStatus
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDateTime

interface CopilotSetEntity : Entity<CopilotSetEntity> {
    var id: Long
    var name: String
    var description: String
    var copilotIds: List<Long> // JSON格式存储作业ID列表
    var views: Long
    var hotScore: Double
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
    val copilotIds = jsonbLongList("copilot_ids").bindTo { it.copilotIds }
    val views = long("views").bindTo { it.views }
    val hotScore = double("hot_score").bindTo { it.hotScore }
    val creatorId = long("creator_id").bindTo { it.creatorId }
    val createTime = datetime("create_time").bindTo { it.createTime }
    val updateTime = datetime("update_time").bindTo { it.updateTime }
    val status = enum<CopilotSetStatus>("status").bindTo { it.status }
    val delete = boolean("delete").bindTo { it.delete }
}

private val json = defaultJson()

private fun Table<*>.jsonbLongList(name: String) = registerColumn(name, JsonbLongListSqlType)

private object JsonbLongListSqlType : SqlType<List<Long>>(Types.OTHER, "jsonb") {
    override fun doSetParameter(ps: PreparedStatement, index: Int, parameter: List<Long>) {
        val pgObject = PGobject().apply {
            type = "json"
            value = json.encodeToString(parameter)
        }
        ps.setObject(index, pgObject)
    }

    override fun doGetResult(rs: ResultSet, index: Int): List<Long> {
        val value = rs.getString(index)
        return json.decodeFromString(value)
    }

}

val Database.copilotSets get() = sequenceOf(CopilotSets)

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
