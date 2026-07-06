package plus.maa.backend.controller.response.message

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import plus.maa.backend.service.model.SiteMessageType
import java.time.LocalDateTime

@Serializable
data class SiteMessageInfo(
    val id: Long,
    val type: SiteMessageType,
    val title: String,
    val content: String,
    val senderId: Long,
    val senderName: String,
    val copilotId: Long?,
    @Contextual
    val createdAt: LocalDateTime,
    @Contextual
    val readAt: LocalDateTime?,
    val isRead: Boolean,
)
