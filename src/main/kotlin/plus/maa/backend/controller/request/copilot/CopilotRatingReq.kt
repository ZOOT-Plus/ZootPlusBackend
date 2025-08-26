package plus.maa.backend.controller.request.copilot

import jakarta.validation.constraints.NotBlank
import plus.maa.backend.config.validation.RatingType

/**
 * @author LoMu
 * Date  2023-01-20 16:25
 */
data class CopilotRatingReq(
    @field:NotBlank(message = "评分作业id不能为空")
    val id: Long,
    @field:NotBlank(message = "评分不能为空")
    @RatingType
    val rating: String,
)
