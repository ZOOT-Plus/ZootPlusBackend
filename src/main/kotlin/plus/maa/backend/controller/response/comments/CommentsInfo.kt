package plus.maa.backend.controller.response.comments

import java.time.LocalDateTime

/**
 * @author LoMu
 * Date  2023-02-20 17:04
 */
data class CommentsInfo(
    val commentId: Long,
    val uploader: String,
    val uploaderId: String,
    // 评论内容,
    val message: String,
    val uploadTime: LocalDateTime,
    val like: Long = 0,
    val dislike: Long = 0,
    val topping: Boolean = false,
    val subCommentsInfos: List<SubCommentsInfo>,
)
