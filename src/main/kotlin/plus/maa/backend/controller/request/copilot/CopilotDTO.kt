package plus.maa.backend.controller.request.copilot

import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable
import plus.maa.backend.repository.entity.Copilot

/**
 * @author LoMu
 * Date  2023-01-10 19:50
 */
@Serializable
data class CopilotDTO(
    // 关卡名
    @field:NotBlank(message = "关卡名不能为空")
    var stageName: String = "",
    // 难度
    val difficulty: Int = 0,
    // 版本号(文档中说明:最低要求 maa 版本号，必选。保留字段)
    @field:NotBlank(message = "最低要求 maa 版本不可为空")
    val minimumRequired: String,
    // 指定干员
    val opers: List<Copilot.Operators>? = null,
    // 群组
    val groups: List<Copilot.Groups>? = null,
    // 战斗中的操作
    val actions: List<Copilot.Action>? = null,
    // 描述
    val doc: Copilot.Doc? = null,
    val notification: Boolean = false,
)
