package plus.maa.backend.repository.entity.gamedata

import kotlinx.serialization.Serializable

/**
 * 地图格子数据
 *
 * @author dragove
 * created on 2022/12/23
 */
@Serializable
data class ArkTilePos(
    val code: String? = null,
    val height: Int = 0,
    val width: Int = 0,
    val levelId: String? = null,
    val name: String? = null,
    val stageId: String? = null,
    val tiles: List<List<Tile>>? = null,
    val view: List<List<Double>>? = null,
) {
    @Serializable
    data class Tile(
        val tileKey: String? = null,
        val heightType: Int? = null,
        val buildableType: Int? = null,
        val isStart: Boolean? = null,
        val isEnd: Boolean? = null,
    )
}
