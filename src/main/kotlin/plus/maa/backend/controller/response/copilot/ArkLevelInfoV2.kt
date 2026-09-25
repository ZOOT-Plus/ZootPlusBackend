package plus.maa.backend.controller.response.copilot

import kotlinx.serialization.Serializable

/**
 * `/arknights/level/v2` 的关卡数据。
 *
 * 与 v1 的 [ArkLevelInfo] 相比只有一处差别：`width`/`height` 可空，默认不返回，
 * 由 `withSize=true` 开启。null 由全局 Json 配置的 `explicitNulls = false` 整个省略键
 * （不是 `"width": null`），该行为由 `ArkLevelSerializationTest` 锁死。
 */
@Serializable
data class ArkLevelInfoV2(
    val levelId: String,
    val stageId: String,
    val catOne: String,
    val catTwo: String,
    val catThree: String,
    val name: String,
    val width: Int? = null,
    val height: Int? = null,
)
