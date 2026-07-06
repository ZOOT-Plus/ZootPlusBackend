package plus.maa.backend.controller.response.message

import kotlinx.serialization.Serializable

@Serializable
data class UnreadMessageCountInfo(
    val unreadCount: Long,
)
