package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

@Serializable
data class ArkEquip(
    val charId: String,
    val typeName1: String,
    val typeName2: String? = null,
    val charEquipOrder: Int,
)
