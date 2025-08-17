package plus.maa.backend.repository.entity

import java.time.Instant

data class UserFollowing(
    val id: String? = null,
    val userId: String,
    val followList: MutableList<String> = mutableListOf(),
    var updatedAt: Instant = Instant.now(),
)
