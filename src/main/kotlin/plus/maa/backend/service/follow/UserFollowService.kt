package plus.maa.backend.service.follow

import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import plus.maa.backend.common.extensions.paginate
import plus.maa.backend.common.extensions.toMaaUserInfo
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.repository.ktorm.UserKtormRepository

@Service
class UserFollowService(
    private val userKtormRepository: UserKtormRepository,
) {

    fun follow(userId: Long, followUserId: Long) {
        check(userId != followUserId) {
            "不能关注自己哦~"
        }
        val followUser = userKtormRepository.findById(followUserId)
        check(followUser != null && followUser.status > 0) {
            "关注的用户不存在哦～"
        }
        userKtormRepository.follow(userId, followUserId)
    }

    fun unfollow(userId: Long, followUserId: Long) {
        userKtormRepository.unfollow(userId, followUserId)
    }

    fun getFollowingList(userId: Long, pageable: Pageable): PageImpl<MaaUserInfo> {
        val res = userKtormRepository.follows(userId).paginate(pageable)
        return PageImpl(res.map { it.toMaaUserInfo() }.toList(), pageable, res.totalElements)
    }

    fun getFansList(userId: Long, pageable: Pageable): PageImpl<MaaUserInfo> {
        val res = userKtormRepository.fans(userId).paginate(pageable)
        return PageImpl(res.map { it.toMaaUserInfo() }.toList(), pageable, res.totalElements)
    }
}
