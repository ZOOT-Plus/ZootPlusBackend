package plus.maa.backend.controller.response.copilot

import java.io.Serializable
import kotlinx.serialization.Serializable as KSerializable

/**
 * @author john180
 */
@KSerializable
data class ArkLevelInfo(
    val levelId: String,
    val stageId: String,
    val catOne: String,
    val catTwo: String,
    val catThree: String,
    val name: String,
    val width: Int = 0,
    val height: Int = 0,
) : Serializable
