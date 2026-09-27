package plus.maa.backend.controller.response.copilot

import kotlinx.serialization.Serializable

/**
 * `/arknights/level/v2` 的响应体。
 *
 * `version` 放在**响应体**而不是响应头：当前 `CorsConfig` 没有配 `exposedHeaders`，
 * 跨域下前端读不到自定义响应头；放 body 不用动 CORS，也不会因将来有人调整 CORS 而静默失效。
 */
@Serializable
data class LevelPayload(
    val version: String,
    val levels: List<ArkLevelInfoV2>,
)
