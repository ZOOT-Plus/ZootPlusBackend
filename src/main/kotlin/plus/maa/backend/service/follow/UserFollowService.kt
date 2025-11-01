package plus.maa.backend.service.follow

import org.ktorm.database.Database
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.repository.ktorm.UserKtormRepository

@Service
class UserFollowService(
    private val database: Database,
    private val userKtormRepository: UserKtormRepository,
) {

    @Transactional
    fun follow(userId: Long, followUserId: Long) {
        // TODO
    }

    @Transactional
    fun unfollow(userId: Long, followUserId: Long) {
        // TODO
    }

    fun getFollowingList(userId: Long, pageable: Pageable): PageImpl<MaaUserInfo> {
        // TODO
        return PageImpl(emptyList(), pageable, 0)
    }

    fun getFansList(userId: Long, pageable: Pageable): PageImpl<MaaUserInfo> {
        // TODO
        return PageImpl(emptyList(), pageable, 0)
    }
}
