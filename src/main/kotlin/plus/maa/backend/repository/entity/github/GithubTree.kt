package plus.maa.backend.repository.entity.github

import kotlinx.serialization.Serializable

/**
 * @author john180
 */
@Serializable
data class GithubTree(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String,
    val url: String?,
)
