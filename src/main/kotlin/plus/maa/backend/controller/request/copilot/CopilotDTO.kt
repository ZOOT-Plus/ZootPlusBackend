package plus.maa.backend.controller.request.copilot

import kotlinx.serialization.Serializable
import plus.maa.backend.repository.entity.Copilot

/**
 * 作业内容解析模型 (ADT)。两种作业类型共享 stage_name / doc / opers / groups，
 * 各自携带类型特有字段。类型由请求体 ([CopilotCUDRequest] 的子类型) 决定，
 * 不依赖内容 JSON 内的判别字段。
 */
@Serializable
sealed interface CopilotContentDTO {
    /** 关卡名（会被规范化为 stageId） */
    var stageName: String

    /** 描述 */
    val doc: Copilot.Doc?

    /** 指定干员 */
    val opers: List<Copilot.Operators>?

    /** 群组 */
    val groups: List<Copilot.Groups>?
}

/**
 * 自动化脚本作业 (MAA copilot 格式)
 */
@Serializable
data class PrtsDTO(
    override var stageName: String = "",
    // 难度
    val difficulty: Int = 0,
    // 版本号(文档中说明:最低要求 maa 版本号，必选。保留字段)
    val minimumRequired: String,
    override val opers: List<Copilot.Operators>? = null,
    override val groups: List<Copilot.Groups>? = null,
    // 战斗中的操作
    val actions: List<Copilot.Action>? = null,
    override val doc: Copilot.Doc? = null,
    val notification: Boolean = false,
) : CopilotContentDTO

/**
 * 视频作业。提供出战干员/群组列表与视频链接，无自动化操作。
 */
@Serializable
data class VideoDTO(
    override var stageName: String = "",
    override val opers: List<Copilot.Operators>? = null,
    override val groups: List<Copilot.Groups>? = null,
    override val doc: Copilot.Doc? = null,
    // 视频链接，用于跳转（或后续前端站内播放）
    val videoUrl: String,
    val notification: Boolean = false,
) : CopilotContentDTO