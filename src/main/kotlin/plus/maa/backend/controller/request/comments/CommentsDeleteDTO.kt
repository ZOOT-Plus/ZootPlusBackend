package plus.maa.backend.controller.request.comments

import jakarta.validation.constraints.NotNull
import kotlinx.serialization.Serializable

/**
 * @author LoMu
 * Date  2023-02-19 10:50
 */
@Serializable
data class CommentsDeleteDTO(
    @field:NotNull(message = "评论id不可为空")
    val commentId: Long,
)