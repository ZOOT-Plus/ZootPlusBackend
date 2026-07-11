package plus.maa.backend.service.model

import kotlinx.serialization.Serializable

/**
 * 作业类型
 */
@Serializable
enum class CopilotType {
    /** 自动化脚本作业（MAA copilot 格式） */
    PRTS,

    /** 视频作业 */
    VIDEO,
}