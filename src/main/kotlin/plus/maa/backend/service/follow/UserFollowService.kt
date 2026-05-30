package plus.maa.backend.service.follow

import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import plus.maa.backend.common.extensions.paginate
import plus.maa.backend.common.extensions.toMaaUserInfo
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.controller.response.user.RelationType
import plus.maa.backend.repository.ktorm.UserKtormRepository
import java.time.ZoneId

@Service
class UserFollowService(
    private val userKtormRepository: UserKtormRepository,
) {

    fun follow(userId: Long, followUserId: Long) {
        check(userId != followUserId) {
            "不能关注自己哦～"
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
        val users = res.toList()
        val targetIds = users.map { it.userId }
        // 查询关注时间
        val createdAtMap = userKtormRepository.getFollowCreatedAtMap(userId, targetIds)
        // 查询哪些目标也关注了我（用于判断 MUTUAL）
        val mutualIds = userKtormRepository.getFollowerTargetIds(targetIds, userId)
        val enriched = users.map { user ->
            val info = user.toMaaUserInfo()
            val relation = if (user.userId in mutualIds) RelationType.MUTUAL else RelationType.FOLLOWING
            val followedAt = createdAtMap[user.userId]?.atZone(ZoneId.systemDefault())?.toInstant()
            info.copy(relation = relation, followedAt = followedAt)
        }
        return PageImpl(enriched, pageable, res.totalElements)
    }

    fun getFansList(userId: Long, pageable: Pageable): PageImpl<MaaUserInfo> {
        val res = userKtormRepository.fans(userId).paginate(pageable)
        val users = res.toList()
        val fanIds = users.map { it.userId }
        // 批量查询粉丝关注我的时间
        val fanCreatedAtMap = userKtormRepository.getFansCreatedAtMap(fanIds, userId)
        // 查询我关注了哪些粉丝（用于判断 MUTUAL）
        val iFollowBackIds = userKtormRepository.getFollowedTargetIds(userId, fanIds)
        val enriched = users.map { user ->
            val info = user.toMaaUserInfo()
            val relation = if (user.userId in iFollowBackIds) RelationType.MUTUAL else RelationType.FOLLOWED_BY
            val followedAt = fanCreatedAtMap[user.userId]?.atZone(ZoneId.systemDefault())?.toInstant()
            info.copy(relation = relation, followedAt = followedAt)
        }
        return PageImpl(enriched, pageable, res.totalElements)
    }
}
