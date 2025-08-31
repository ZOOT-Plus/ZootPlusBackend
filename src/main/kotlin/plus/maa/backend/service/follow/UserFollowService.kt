package plus.maa.backend.service.follow

import org.ktorm.database.Database
import org.ktorm.dsl.inList
import org.ktorm.entity.filter
import org.ktorm.entity.toList
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.UserFansEntity
import plus.maa.backend.repository.entity.UserFollowingEntity
import plus.maa.backend.repository.entity.fansList
import plus.maa.backend.repository.entity.followList
import plus.maa.backend.repository.entity.setFansList
import plus.maa.backend.repository.entity.setFollowList
import plus.maa.backend.repository.entity.users
import plus.maa.backend.repository.ktorm.UserFansKtormRepository
import plus.maa.backend.repository.ktorm.UserFollowingKtormRepository
import plus.maa.backend.repository.ktorm.UserKtormRepository
import java.time.LocalDateTime

@Service
class UserFollowService(
    private val database: Database,
    private val userKtormRepository: UserKtormRepository,
    private val userFollowingKtormRepository: UserFollowingKtormRepository,
    private val userFansKtormRepository: UserFansKtormRepository,
) {

    @Transactional
    fun follow(userId: String, followUserId: String) = updateFollowingRel(userId, followUserId, true)

    @Transactional
    fun unfollow(userId: String, followUserId: String) = updateFollowingRel(userId, followUserId, false)

    private fun updateFollowingRel(followerId: String, followeeId: String, add: Boolean) {
        val opStr = if (add) "关注" else "取关"
        require(followerId != followeeId) { "不能${opStr}自己" }
        if (!userKtormRepository.existsById(followeeId)) {
            throw IllegalArgumentException("${opStr}对象不存在")
        }

        // 更新关注列表
        updateFollowingList(followerId, followeeId, add)
        // 更新粉丝列表
        updateFansList(followeeId, followerId, add)
    }

    private fun updateFollowingList(followerId: String, followeeId: String, add: Boolean) {
        val following = userFollowingKtormRepository.findByUserId(followerId) ?: run {
            val newFollowing = UserFollowingEntity {
                this.id = "following_$followerId"
                this.userId = followerId
                this.updatedAt = LocalDateTime.now()
            }
            newFollowing.setFollowList(mutableListOf())
            userFollowingKtormRepository.insertEntity(newFollowing)
            newFollowing
        }

        val currentList = following.followList.toMutableList()
        if (add) {
            if (!currentList.contains(followeeId)) {
                currentList.add(followeeId)
            }
        } else {
            currentList.remove(followeeId)
        }

        following.setFollowList(currentList)
        following.updatedAt = LocalDateTime.now()
        userFollowingKtormRepository.updateEntity(following)

        // 更新用户关注数量
        updateFansCount(followerId, currentList)
    }

    private fun updateFansList(userId: String, fanId: String, add: Boolean) {
        val fans = userFansKtormRepository.findByUserId(userId) ?: run {
            val newFans = UserFansEntity {
                this.id = "fans_$userId"
                this.userId = userId
                this.updatedAt = LocalDateTime.now()
            }
            newFans.setFansList(mutableListOf())
            userFansKtormRepository.insertEntity(newFans)
            newFans
        }

        val currentList = fans.fansList.toMutableList()
        if (add) {
            if (!currentList.contains(fanId)) {
                currentList.add(fanId)
            }
        } else {
            currentList.remove(fanId)
        }

        fans.setFansList(currentList)
        fans.updatedAt = LocalDateTime.now()
        userFansKtormRepository.updateEntity(fans)

        // 更新用户粉丝数量
        updateFansCount(userId, currentList)
    }

    private fun updateFansCount(userId: String, currentList: MutableList<String>) {
        val user: UserEntity? = userKtormRepository.findById(userId)
        user?.let { userEntity: UserEntity ->
            userEntity.fansCount = currentList.size
            userKtormRepository.updateEntity(userEntity)
        }
    }

    fun getFollowingList(userId: String, pageable: Pageable): PageImpl<MaaUserInfo> {
        val following = userFollowingKtormRepository.findByUserId(userId)
        val followingIds = following?.followList ?: emptyList()
        return getUserPageFromIds(followingIds, pageable)
    }

    fun getFansList(userId: String, pageable: Pageable): PageImpl<MaaUserInfo> {
        val fans = userFansKtormRepository.findByUserId(userId)
        val fansIds = fans?.fansList ?: emptyList()
        return getUserPageFromIds(fansIds, pageable)
    }

    private fun getUserPageFromIds(userIds: List<String>, pageable: Pageable): PageImpl<MaaUserInfo> {
        val totalCount = userIds.size.toLong()
        val offset = pageable.pageNumber * pageable.pageSize
        val limit = pageable.pageSize

        val pagedIds = userIds.drop(offset).take(limit)

        val users = if (pagedIds.isEmpty()) {
            emptyList()
        } else {
            database.users.filter { it.userId inList pagedIds }.toList()
        }

        val userInfos = users.map { MaaUserInfo(it) }
        return PageImpl(userInfos, pageable, totalCount)
    }
}
