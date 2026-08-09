package plus.maa.backend.repository.entity

import java.time.LocalDateTime

/**
 * 用户关注关系实体。
 *
 * 复合主键 (user_id, follow_user_id)。
 */
data class UserFollowEntity(
    var userId: Long = 0,
    var followUserId: Long = 0,
    var specialFollow: Boolean = false,
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
