package plus.maa.backend.controller.request.copilot

import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable
import plus.maa.backend.config.validation.JsonSchemaMatch
import plus.maa.backend.config.validation.JsonSchemaMatchValidator
import plus.maa.backend.service.model.CopilotSetStatus

@Serializable
data class CopilotCUDRequest(
    @field:NotBlank(message = "作业内容必填")
    @JsonSchemaMatch(schema = JsonSchemaMatchValidator.COPILOT_SCHEMA_JSON)
    val content: String = "",
    val id: Long? = null,
    val status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
)
