package plus.maa.backend.controller.request.copilotset

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import kotlinx.serialization.Serializable

/**
 * @author dragove
 * create on 2024-01-02
 */
@Schema(title = "作业集新增作业列表请求")
@Serializable
data class CopilotSetModCopilotsReq(
    @field:Schema(title = "作业集id")
    @field:NotNull(message = "作业集id必填")
    val id: Long,
    @field:Schema(title = "添加/删除收藏的作业id列表")
    @field:NotEmpty(message = "添加/删除作业id列表不可为空")
    val copilotIds: MutableList<Long>,
)