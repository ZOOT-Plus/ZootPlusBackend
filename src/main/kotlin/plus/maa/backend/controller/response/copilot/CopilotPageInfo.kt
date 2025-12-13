package plus.maa.backend.controller.response.copilot

import kotlinx.serialization.Serializable

/**
 * @author LoMu
 * Date  2022-12-27 12:39
 */
@Serializable
data class CopilotPageInfo(
    val hasNext: Boolean,
    val page: Int,
    val total: Long,
    val data: List<CopilotInfo>,
)
