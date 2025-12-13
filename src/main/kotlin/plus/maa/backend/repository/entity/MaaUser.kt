package plus.maa.backend.repository.entity

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * @author AnselYuki
 */
@Serializable
data class MaaUser(
    val userId: String? = null,
    var userName: String,
    val email: String,
    var password: String,
    var status: Int = 0,
    @Contextual
    var pwdUpdateTime: Instant = Instant.MIN,
    var followingCount: Int = 0,
    var fansCount: Int = 0,
) {

    companion object {
        val UNKNOWN: MaaUser = MaaUser(
            userId = "0",
            userName = "未知用户:(",
            email = "unknown@unkown.unkown",
            password = "unknown",
        )
    }
}
