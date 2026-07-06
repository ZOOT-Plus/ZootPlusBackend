package plus.maa.backend.service

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.controller.response.message.SiteMessageInfo
import plus.maa.backend.controller.response.message.UnreadMessageCountInfo
import plus.maa.backend.repository.entity.SiteMessageEntity
import plus.maa.backend.repository.ktorm.SiteMessageKtormRepository
import plus.maa.backend.repository.ktorm.UserKtormRepository
import plus.maa.backend.service.model.SiteMessageType
import java.time.LocalDateTime

@Service
class SiteMessageService(
    private val siteMessageKtormRepository: SiteMessageKtormRepository,
    private val userKtormRepository: UserKtormRepository,
) {
    fun notifyCopilotPublished(senderId: Long, copilotId: Long, copilotTitle: String) {
        val receiverIds = userKtormRepository.getSpecialFollowerIds(senderId).distinct()
        if (receiverIds.isEmpty()) return
        val sender = userKtormRepository.findById(senderId) ?: return
        val now = LocalDateTime.now()
        val displayTitle = copilotTitle.ifBlank { "未命名作业" }
        val messages = receiverIds.map { receiverId ->
            SiteMessageEntity {
                this.receiverId = receiverId
                this.senderId = senderId
                this.senderName = sender.userName
                this.type = SiteMessageType.COPILOT_PUBLISHED
                this.title = "特关作者发布了新作业"
                this.content = "你特关的作者 @${sender.userName} 发布了新作业《$displayTitle》"
                this.copilotId = copilotId
                this.readAt = null
                this.createdAt = now
            }
        }
        siteMessageKtormRepository.insertAll(messages)
    }

    fun list(userId: Long, page: Int, size: Int, unreadOnly: Boolean): PagedDTO<SiteMessageInfo> {
        val realPage = page.coerceAtLeast(1)
        val realSize = size.coerceAtLeast(1)
        val pageable = PageRequest.of(realPage - 1, realSize)
        val messages = siteMessageKtormRepository.findByReceiverId(userId, unreadOnly, pageable)
        return PagedDTO(
            hasNext = messages.hasNext(),
            page = messages.pageable.pageNumber + 1,
            total = messages.totalElements,
            data = messages.content.map(::toInfo),
        )
    }

    fun unreadCount(userId: Long): UnreadMessageCountInfo {
        return UnreadMessageCountInfo(siteMessageKtormRepository.countUnreadByReceiverId(userId))
    }

    fun markRead(userId: Long, id: Long) {
        siteMessageKtormRepository.markRead(userId, id, LocalDateTime.now())
    }

    fun markAllRead(userId: Long) {
        siteMessageKtormRepository.markAllRead(userId, LocalDateTime.now())
    }

    private fun toInfo(message: SiteMessageEntity) = SiteMessageInfo(
        id = message.id,
        type = message.type,
        title = message.title,
        content = message.content,
        senderId = message.senderId,
        senderName = message.senderName,
        copilotId = message.copilotId,
        createdAt = message.createdAt,
        readAt = message.readAt,
        isRead = message.readAt != null,
    )
}
