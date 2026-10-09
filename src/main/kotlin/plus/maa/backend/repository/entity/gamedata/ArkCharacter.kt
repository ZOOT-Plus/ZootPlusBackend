package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

@Serializable
data class ArkCharacter(
    val name: String,
    val profession: String,
    val rarity: Int,
    val subProfessionId: String? = null,
) {
    var id: String? = null
}
