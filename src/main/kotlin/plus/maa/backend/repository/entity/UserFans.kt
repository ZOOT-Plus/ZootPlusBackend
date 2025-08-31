package plus.maa.backend.repository.entity

import java.time.Instant

data class UserFans(
    val id: String? = null,
    val userId: String,
    val fansList: MutableList<String> = mutableListOf(),
    var updatedAt: Instant = Instant.now(),
)
