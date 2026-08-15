package plus.maa.backend.repository.entity

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import java.time.LocalDateTime

@Serializable
data class CopilotEntity(
    // 自增数字ID，0 表示未插入
    var copilotId: Long = 0,

    var type: CopilotType = CopilotType.PRTS,

    var stageName: String = "",

    var uploaderId: Long = 0,

    var views: Long = 0L,

    var ratingLevel: Int = 0,

    // 评级比率 十分之一代表半星
    var ratingRatio: Double = 0.0,

    var likeCount: Long = 0L,

    var dislikeCount: Long = 0L,

    var hotScore: Double = 0.0,

    // 文档字段，用于搜索
    var title: String = "",

    var details: String? = null,

    @Contextual
    var firstUploadTime: LocalDateTime = LocalDateTime.now(),

    @Contextual
    var uploadTime: LocalDateTime = LocalDateTime.now(),

    var content: String = "",

    /**
     * 作业状态，后端默认设置为公开以兼容历史逻辑
     * [plus.maa.backend.service.model.CopilotSetStatus]
     */
    var status: CopilotSetStatus = CopilotSetStatus.PUBLIC,

    var commentStatus: CommentStatus = CommentStatus.ENABLED,

    var delete: Boolean = false,

    @Contextual
    var deleteTime: LocalDateTime? = null,

    var notification: Boolean = false,
)
