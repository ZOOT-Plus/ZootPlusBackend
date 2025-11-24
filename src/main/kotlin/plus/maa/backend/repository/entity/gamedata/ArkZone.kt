package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

@Serializable
data class ArkZone(
    /**
     * 例: main_1
     */
    val zoneID: String,
    /**
     * 例: 第一章
     */
    val zoneNameFirst: String?,
    /**
     * 例: 黑暗时代·下
     */
    val zoneNameSecond: String?,
)