package plus.maa.backend.controller.request.user

import kotlinx.serialization.Serializable

@Serializable
data class RefreshReq(
    val refreshToken: String,
)