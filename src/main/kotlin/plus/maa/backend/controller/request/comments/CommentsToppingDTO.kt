package plus.maa.backend.controller.request.comments

import jakarta.validation.constraints.NotNull

/**
 * @author Lixuhuilll
 * Date  2023-08-17 11:20
 */
data class CommentsToppingDTO(
    @field:NotNull(message = "评论id不可为空")
    val commentId: Long,
    // 是否将指定评论置顶
    val topping: Boolean = true,
)
