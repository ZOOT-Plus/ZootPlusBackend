package plus.maa.backend.controller.request.copilotset

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import plus.maa.backend.service.model.CopilotSetStatus

/**
 * @author dragove
 * create on 2024-01-02
 */
@Schema(title = "作业集更新请求")
data class CopilotSetUpdateReq(
    @field:NotNull(message = "作业集id不能为空")
    val id: Long,
    @field:Schema(title = "作业集名称")
    val name: String?,
    @field:Schema(title = "作业集额外描述")
    val description: String?,
    @field:Schema(title = "作业集公开状态", enumAsRef = true)
    val status: CopilotSetStatus?,
    @field:Schema(title = "作业集作业列表")
    val copilotIds: MutableList<Long>?,
)
