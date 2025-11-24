package plus.maa.backend.controller.request.comments

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotNull
import kotlinx.serialization.Serializable

/**
 * @author LoMu
 * Date  2023-02-20 17:13
 */
@Serializable
data class CommentsQueriesDTO(
    @field:NotNull(message = "作业id不可为空")
    val copilotId: Long,
    val page: Int = 0,
    @field:Max(value = 50, message = "单页大小不得超过50")
    val limit: Int = 10,
    val desc: Boolean = true,
    val orderBy: String? = null,
    val justSeeId: Long? = null,
)