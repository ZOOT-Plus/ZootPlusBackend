package plus.maa.backend.repository.entity

import java.time.Instant

/**
 * 用户实体。
 *
 * 注意：`userId == 0L` 表示新实体（INSERT 时省略 user_id，走自增回填，回填为原地修改）；
 * `userId != 0L` 时 INSERT 显式携带 user_id。
 */
data class UserEntity(
    var userId: Long = 0,
    var userName: String = "",
    var email: String = "",
    var password: String = "",
    var status: Int = 0,
    var pwdUpdateTime: Instant = Instant.MIN,
    var followingCount: Int = 0,
    var fansCount: Int = 0,
) {
    companion object {
        val UNKNOWN = UserEntity(
            userId = 0L,
            userName = "未知用户",
            email = "unknown@unknown.unknown",
            password = "unknown",
        )
    }
}
