package plus.maa.backend.controller.response.user

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class FollowStatusInfo(
    val following: Boolean,
    val specialFollow: Boolean,
    val relation: RelationType,
    @Contextual
    val followedAt: Instant? = null,
)
