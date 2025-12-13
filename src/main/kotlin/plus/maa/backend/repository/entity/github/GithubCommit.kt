package plus.maa.backend.repository.entity.github

import kotlinx.serialization.Serializable

/**
 * @author john180
 */
@Serializable
data class GithubCommit(
    val sha: String,
)
