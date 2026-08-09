package plus.maa.backend.repository.entity

import plus.maa.backend.service.model.SiteMessageType
import java.time.LocalDateTime

data class SiteMessageEntity(
    var id: Long = 0,
    var receiverId: Long,
    var senderId: Long,
    var senderName: String,
    var type: SiteMessageType,
    var title: String,
    var content: String,
    var copilotId: Long? = null,
    var readAt: LocalDateTime? = null,
    var createdAt: LocalDateTime,
)
