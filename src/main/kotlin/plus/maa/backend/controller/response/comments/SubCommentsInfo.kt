package plus.maa.backend.controller.response.comments

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * @author LoMu
 * Date  2023-02-20 17:05
 */
@Serializable
data class SubCommentsInfo(
    val commentId: Long,
    val uploader: String,
    val uploaderId: String,
    // 评论内容,
    val message: String,
    @Contextual
    val uploadTime: LocalDateTime,
    val like: Long = 0,
    val dislike: Long = 0,
    val fromCommentId: Long,
    val mainCommentId: Long,
    val deleted: Boolean = false,
)
