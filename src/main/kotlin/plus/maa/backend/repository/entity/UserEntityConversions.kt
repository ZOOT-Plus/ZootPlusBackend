package plus.maa.backend.repository.entity

import plus.maa.backend.controller.response.user.MaaUserInfo

/**
 * UserEntity → 展示/传输模型 的纯映射扩展。
 */
fun UserEntity.toMaaUser(): MaaUser {
    return MaaUser(
        userId = this.userId.toString(),
        userName = this.userName,
        email = this.email,
        password = this.password,
        status = this.status,
        pwdUpdateTime = this.pwdUpdateTime,
        followingCount = this.followingCount,
        fansCount = this.fansCount,
    )
}

fun UserEntity.toMaaUserInfo(): MaaUserInfo {
    return MaaUserInfo(
        id = this.userId.toString(),
        userName = this.userName,
        followingCount = this.followingCount,
        fansCount = this.fansCount,
    )
}
