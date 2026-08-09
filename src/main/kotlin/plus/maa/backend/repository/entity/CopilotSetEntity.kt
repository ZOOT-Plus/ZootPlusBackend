package plus.maa.backend.repository.entity

import org.springframework.util.Assert
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

data class CopilotSetEntity(
    var id: Long = 0,
    var name: String = "",
    var description: String = "",
    var copilotIds: List<Long> = emptyList(), // JSON格式存储作业ID列表
    var views: Long = 0,
    var hotScore: Double = 0.0,
    var creatorId: Long = 0,
    var createTime: LocalDateTime,
    var updateTime: LocalDateTime,
    var status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
    var delete: Boolean = false,
)

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
