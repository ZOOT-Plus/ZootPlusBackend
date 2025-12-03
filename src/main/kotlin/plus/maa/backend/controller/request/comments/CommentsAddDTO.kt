package plus.maa.backend.controller.request.comments

import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable
import org.hibernate.validator.constraints.Length
import plus.maa.backend.service.model.CommentStatus

/**
 * @author LoMu
 * Date  2023-02-17 14:58
 */
@Serializable
data class CommentsAddDTO(
    /**
     * 评论内容
     * 500字长度为对作者的限制，对于非作者限制为150字
     * 在业务层中实现
     */
    @field:Length(min = 1, max = 500, message = "评论内容不可超过500字，请删减")
    @field:NotBlank(message = "请填写评论内容")
    val message: String,
    /**
     * 评论所在作业的 id
     */
    val copilotId: Long,
    /**
     * 被回复评论的 id
     */
    val fromCommentId: Long? = null,
    /**
     * 是否接收通知
     */
    val notification: Boolean = true,
    /**
     * 是否允许评论
     */
    val commentStatus: CommentStatus = CommentStatus.ENABLED,
)
