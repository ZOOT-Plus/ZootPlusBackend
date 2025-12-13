package plus.maa.backend.controller.response.copilot

import kotlinx.serialization.Serializable

/**
 * @author john180
 */
@Serializable
data class ArkLevelInfo(
    val levelId: String,
    val stageId: String,
    val catOne: String,
    val catTwo: String,
    val catThree: String,
    val name: String,
    val width: Int = 0,
    val height: Int = 0,
)
