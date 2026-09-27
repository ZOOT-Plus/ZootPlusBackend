package plus.maa.backend.controller.response.copilot

import kotlinx.serialization.Serializable

/**
 * `/arknights/level/v2/version` 的响应体：一次往返拿到两个变体的当前版本号。
 */
@Serializable
data class LevelVersions(
    val full: String,
    val lite: String,
)
