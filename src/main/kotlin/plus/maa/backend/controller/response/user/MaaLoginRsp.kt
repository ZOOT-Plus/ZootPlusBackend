package plus.maa.backend.controller.response.user

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class MaaLoginRsp(
    val token: String,
    @Contextual
    val validBefore: Instant,
    @Contextual
    val validAfter: Instant,
    val refreshToken: String,
    @Contextual
    val refreshTokenValidBefore: Instant,
    @Contextual
    val refreshTokenValidAfter: Instant,
    val userInfo: MaaUserInfo,
)
