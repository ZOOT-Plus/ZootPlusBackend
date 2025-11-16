package plus.maa.backend.controller.request.comments

import jakarta.validation.constraints.NotNull

/**
 * @author LoMu
 * Date  2023-02-19 10:50
 */
data class CommentsDeleteDTO(
    @field:NotNull(message = "评论id不可为空")
    val commentId: Long,
)
