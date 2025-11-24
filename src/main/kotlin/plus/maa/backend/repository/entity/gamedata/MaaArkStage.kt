package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

@Serializable
data class MaaArkStage(
    /**
     * 例: CB-EX8
     */
    val code: String,
    /**
     * 例:  act5d0_ex08
     */
    val stageId: String?,
)
