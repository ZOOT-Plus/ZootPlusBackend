package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

@Serializable
data class ArkStage(
    /**
     * 关卡ID, 需转换为全小写后使用<br></br>
     * 例: Activities/ACT5D0/level_act5d0_ex08
     */
    val levelId: String?,
    /**
     * 例: act14d7_zone2
     */
    val zoneId: String,
    /**
     * 例:  act5d0_ex08
     */
    val stageId: String,
    /**
     * 例: CB-EX8
     */
    val code: String,
)