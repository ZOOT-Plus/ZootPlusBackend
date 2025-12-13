package plus.maa.backend.controller.response.user

import kotlinx.serialization.Serializable
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity

/**
 * 用户可对外公开的信息
 *
 * @author AnselYuki
 */
@Serializable
data class MaaUserInfo(
    val id: String,
    val userName: String,
    val activated: Boolean = false,
    val followingCount: Int = 0,
    val fansCount: Int = 0,
) {
    constructor(user: MaaUser) : this(
        id = user.userId!!,
        userName = user.userName,
        activated = user.status == 1,
        followingCount = user.followingCount,
        fansCount = user.fansCount,
    )
    constructor(user: UserEntity) : this(
        id = user.userId.toString(),
        userName = user.userName,
        activated = user.status == 1,
        followingCount = user.followingCount,
        fansCount = user.fansCount,
    )
}
