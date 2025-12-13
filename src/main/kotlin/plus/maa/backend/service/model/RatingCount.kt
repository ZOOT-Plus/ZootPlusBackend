package plus.maa.backend.service.model

import kotlinx.serialization.Serializable

@Serializable
data class RatingCount(
    val key: String,
    val count: Long = 0,
)