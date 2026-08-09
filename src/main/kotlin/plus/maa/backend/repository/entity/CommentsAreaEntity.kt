package plus.maa.backend.repository.entity

import java.time.LocalDateTime

/**
 * comments_area 表实体。
 *
 * - 属性保持可变（var）：服务层"读出 → 改字段 → updateEntity"依赖原地修改。
 */
data class CommentsAreaEntity(
    var id: Long = 0,
    var copilotId: Long = 0,
    var fromCommentId: Long? = null,
    var uploaderId: Long = 0,
    var message: String = "",
    var likeCount: Long = 0,
    var dislikeCount: Long = 0,
    var uploadTime: LocalDateTime = LocalDateTime.now(),
    var topping: Boolean = false,
    var delete: Boolean = false,
    var deleteTime: LocalDateTime? = null,
    var mainCommentId: Long? = null,
    var notification: Boolean = false,
)
