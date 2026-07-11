package plus.maa.backend.controller.response.copilot

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import java.time.LocalDateTime

@Serializable
data class CopilotInfo(
    val id: Long,
    // 作业类型
    val type: CopilotType,
    // 视频链接，仅 VIDEO 类型有值，PRTS 为 null
    val videoUrl: String? = null,
    @Contextual
    val uploadTime: LocalDateTime,
    val uploaderId: String,
    val uploader: String,
    // 用于前端显示的格式化后的干员信息 [干员名]::[技能]
    val views: Long = 0,
    val hotScore: Double = 0.0,
    var available: Boolean = false,
    var ratingLevel: Int = 0,
    var notEnoughRating: Boolean = false,
    var ratingRatio: Double = 0.0,
    var ratingType: Int = 0,
    val commentsCount: Long = 0,
    val content: String,
    val like: Long = 0,
    val dislike: Long = 0,
    val commentStatus: CommentStatus = CommentStatus.ENABLED,
    val status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
)
