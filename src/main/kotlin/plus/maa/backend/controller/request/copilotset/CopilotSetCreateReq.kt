package plus.maa.backend.controller.request.copilotset

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kotlinx.serialization.Serializable
import plus.maa.backend.common.model.CopilotSetType
import plus.maa.backend.service.model.CopilotSetStatus

/**
 * @author dragove
 * create on 2024-01-01
 */
@Schema(title = "作业集创建请求")
@Serializable
data class CopilotSetCreateReq(
    @field:Schema(title = "作业集名称")
    @field:NotBlank(message = "作业集名称不能为空")
    val name: String,
    @field:Schema(title = "作业集额外描述")
    val description: String = "",
    @field:Schema(title = "初始关联作业列表")
    @field:NotNull(message = "作业id列表字段不能为null")
    @field:Size(
        max = 1000,
        message = "作业集作业列表最大只能为1000",
    )
    override val copilotIds: MutableList<Long>,
    @field:Schema(title = "作业集公开状态", enumAsRef = true)
    @field:NotNull(message = "作业集公开状态不能为null")
    val status: CopilotSetStatus,
) : CopilotSetType
