package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

@Serializable
data class ArkActivity(
    val id: String,
    val name: String,
)