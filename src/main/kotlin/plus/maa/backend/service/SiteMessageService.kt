package plus.maa.backend.service

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.controller.response.message.SiteMessageInfo
import plus.maa.backend.controller.response.message.UnreadMessageCountInfo
import plus.maa.backend.repository.entity.SiteMessageEntity
import plus.maa.backend.repository.ktorm.SiteMessageRepository
import plus.maa.backend.repository.ktorm.UserRepository
import java.time.LocalDateTime

@Service
class SiteMessageService(
    private val siteMessageRepository: SiteMessageRepository,
    private val userRepository: UserRepository,
) {
    fun notifyCopilotPublished(senderId: Long, copilotId: Long, copilotTitle: String): Int {
        val sender = userRepository.findById(senderId) ?: return 0
        val displayTitle = copilotTitle.ifBlank { "未命名作业" }
        val title = "特关作者发布了新作业"
        val content = "你特关的作者 @${sender.userName} 发布了新作业《$displayTitle》"
        // 无特关粉丝时下面的 INSERT...SELECT 会自然插入 0 行并返回 0
        return siteMessageRepository.insertCopilotPublishedNotifications(
            senderId = senderId,
            senderName = sender.userName,
            copilotId = copilotId,
            title = title,
            content = content,
            createdAt = LocalDateTime.now(),
        )
    }

    fun list(userId: Long, page: Int, size: Int, unreadOnly: Boolean): PagedDTO<SiteMessageInfo> {
        val realPage = page.coerceAtLeast(1)
        val realSize = size.coerceAtLeast(1)
        val pageable = PageRequest.of(realPage - 1, realSize)
        val messages = siteMessageRepository.findByReceiverId(userId, unreadOnly, pageable)
        return PagedDTO(
            hasNext = messages.hasNext(),
            page = messages.pageable.pageNumber + 1,
            total = messages.totalElements,
            data = messages.content.map(::toInfo),
        )
    }

    fun unreadCount(userId: Long): UnreadMessageCountInfo {
        return UnreadMessageCountInfo(siteMessageRepository.countUnreadByReceiverId(userId))
    }

    fun markRead(userId: Long, id: Long): Boolean {
        return siteMessageRepository.markRead(userId, id, LocalDateTime.now())
    }

    fun markAllRead(userId: Long): Int {
        return siteMessageRepository.markAllRead(userId, LocalDateTime.now())
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
