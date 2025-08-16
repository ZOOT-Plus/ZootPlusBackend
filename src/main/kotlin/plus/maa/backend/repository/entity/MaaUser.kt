package plus.maa.backend.repository.entity

import com.fasterxml.jackson.annotation.JsonInclude
import java.io.Serializable
import java.time.Instant

/**
 * @author AnselYuki
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MaaUser(
    val userId: String? = null,
    var userName: String,
    val email: String,
    var password: String,
    var status: Int = 0,
    var pwdUpdateTime: Instant = Instant.MIN,
    var followingCount: Int = 0,
    var fansCount: Int = 0,
) : Serializable {

    companion object {
        val UNKNOWN: MaaUser = MaaUser(
            userId = "",
            userName = "未知用户:(",
            email = "unknown@unkown.unkown",
            password = "unknown",
        )
    }
}
