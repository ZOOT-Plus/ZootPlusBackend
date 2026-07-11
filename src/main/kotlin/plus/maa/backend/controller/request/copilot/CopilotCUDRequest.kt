package plus.maa.backend.controller.request.copilot

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import plus.maa.backend.config.validation.JsonSchemaMatch
import plus.maa.backend.config.validation.JsonSchemaMatchValidator
import plus.maa.backend.service.model.CopilotSetStatus

/**
 * 作业 CUD 请求 (ADT)。作业类型由请求子类型决定，`type` 为 kotlinx/Jackson 的判别字段。
 *
 * - 运行时反序列化使用 kotlinx-serialization，判别字段默认为 `type`，
 *   值由子类的 [SerialName] 提供。为兼容不携带 `type` 的旧客户端，
 *   缺失/为 null 的 `type` 默认视作 PRTS（见 [CopilotCUDRequestSerializer]）。
 * - OpenAPI/TS 客户端生成由 SpringDoc 通过 Jackson 反射完成，因此同时挂载 Jackson
 *   的 [JsonTypeInfo]/[JsonSubTypes]，与 kotlinx 保持相同的判别字段与取值。
 *   两套注解互不干扰：Jackson 忽略 [SerialName]，kotlinx 忽略 Jackson 注解。
 */
@Serializable(with = CopilotCUDRequestSerializer::class)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = PrtsCUDRequest::class, name = "PRTS"),
    JsonSubTypes.Type(value = VideoCUDRequest::class, name = "VIDEO"),
)
sealed interface CopilotCUDRequest {
    val content: String
    val id: Long?
    val status: CopilotSetStatus
}

@Serializable
@SerialName("PRTS")
data class PrtsCUDRequest(
    @field:NotBlank(message = "作业内容必填")
    @JsonSchemaMatch(schema = JsonSchemaMatchValidator.COPILOT_SCHEMA_JSON)
    override val content: String = "",
    override val id: Long? = null,
    override val status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
) : CopilotCUDRequest

@Serializable
@SerialName("VIDEO")
data class VideoCUDRequest(
    @field:NotBlank(message = "作业内容必填")
    @JsonSchemaMatch(schema = JsonSchemaMatchValidator.COPILOT_VIDEO_SCHEMA_JSON)
    override val content: String = "",
    override val id: Long? = null,
    override val status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
) : CopilotCUDRequest

/**
 * 删除作业仅需 id，与类型无关，故独立出 CUD 的 sealed 体系，避免要求客户端附带 type 判别字段。
 */
@Serializable
data class CopilotDeleteRequest(
    val id: Long? = null,
)