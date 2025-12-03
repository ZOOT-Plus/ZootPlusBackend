package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

@Serializable
data class ArkTower(
    val id: String,
    val name: String,
    val subName: String,
)