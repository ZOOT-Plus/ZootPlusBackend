package plus.maa.backend.controller.request.comments

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kotlinx.serialization.Serializable
import plus.maa.backend.config.validation.RatingType

/**
 * @author LoMu
 * Date  2023-02-19 13:39
 */
@Serializable
data class CommentsRatingDTO(
    @field:NotNull(message = "评分id不可为空")
    val commentId: Long,
    @field:NotBlank(message = "评分不能为空")
    @field:RatingType
    val rating: String,
)
