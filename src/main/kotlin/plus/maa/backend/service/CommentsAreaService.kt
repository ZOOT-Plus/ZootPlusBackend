package plus.maa.backend.service

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import plus.maa.backend.common.extensions.requireNotNull
import plus.maa.backend.controller.request.comments.CommentsAddDTO
import plus.maa.backend.controller.request.comments.CommentsQueriesDTO
import plus.maa.backend.controller.request.comments.CommentsRatingDTO
import plus.maa.backend.controller.request.comments.CommentsToppingDTO
import plus.maa.backend.controller.response.MaaResultException
import plus.maa.backend.controller.response.comments.CommentsAreaInfo
import plus.maa.backend.controller.response.comments.CommentsInfo
import plus.maa.backend.controller.response.comments.SubCommentsInfo
import plus.maa.backend.repository.entity.CommentsAreaEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.ktorm.CommentsAreaKtormRepository
import plus.maa.backend.repository.ktorm.CopilotKtormRepository
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.RatingType
import plus.maa.backend.service.sensitiveword.SensitiveWordService
import java.time.LocalDateTime
import plus.maa.backend.cache.InternalComposeCache as Cache

/**
 * @author LoMu
 * Date  2023-02-17 15:00
 */
@Service
class CommentsAreaService(
    private val commentsAreaKtormRepository: CommentsAreaKtormRepository,
    private val ratingService: RatingService,
    private val copilotKtormRepository: CopilotKtormRepository,
    private val userService: UserService,
    private val emailService: EmailService,
    private val sensitiveWordService: SensitiveWordService,
) {
    /**
     * 评论
     * 每个评论都有一个uuid加持
     *
     * @param userId         登录用户 id
     * @param commentsAddDTO CommentsRequest
     */
    fun addComments(userId: String, commentsAddDTO: CommentsAddDTO) {
        sensitiveWordService.validate(commentsAddDTO.message)
        val copilotId = commentsAddDTO.copilotId
        val copilot = copilotKtormRepository.findByCopilotId(copilotId).requireNotNull { "作业不存在" }

        if (copilot.commentStatus == CommentStatus.DISABLED && userId != copilot.uploaderId) {
            throw MaaResultException("评论区已被禁用")
        }

        if (userId != copilot.uploaderId) {
            require(commentsAddDTO.message.length <= 150) { "评论内容不可超过150字，请删减" }
        }

        val parentCommentId = commentsAddDTO.fromCommentId?.ifBlank { null }
        // 指定了回复对象但该对象不存在时抛出异常
        val parentComment = parentCommentId?.let { id -> requireCommentsAreaById(id) { "回复的评论不存在" } }

        notifyRelatedUser(userId, commentsAddDTO.message, copilot, parentComment)

        val comment = CommentsAreaEntity {
            this.id = ""
            this.copilotId = copilotId
            this.uploaderId = userId
            this.fromCommentId = parentComment?.id
            this.mainCommentId = parentComment?.run { mainCommentId ?: id }
            this.message = commentsAddDTO.message
            this.notification = commentsAddDTO.notification
            this.uploadTime = LocalDateTime.now()
            this.likeCount = 0L
            this.dislikeCount = 0L
            this.topping = false
            this.delete = false
            this.deleteTime = null
        }
        commentsAreaKtormRepository.insertEntity(comment)
        Cache.invalidateCommentCountById(copilotId)
    }

    private fun notifyRelatedUser(replierId: String, message: String, copilot: CopilotEntity, parentComment: CommentsAreaEntity?) {
        if (parentComment?.notification == false) return
        val receiverId = parentComment?.uploaderId ?: copilot.uploaderId
        if (receiverId == replierId) return

        val userMap = userService.findByUsersId(listOf(receiverId, replierId))
        val receiver = userMap[receiverId] ?: return
        val replier = userMap.getOrDefault(replierId)

        val targetMsg = parentComment?.message ?: copilot.title
        emailService.sendCommentNotification(
            receiver.email,
            receiver.userName,
            targetMsg,
            replier.userName,
            message,
            "?op=${copilot.copilotId}",
        )
    }

    fun deleteComments(userId: String, commentsId: String) {
        val commentsArea = requireCommentsAreaById(commentsId)
        // 允许作者删除评论
        val copilot = copilotKtormRepository.findByCopilotId(commentsArea.copilotId)
        require(userId == copilot?.uploaderId || userId == commentsArea.uploaderId) { "您无法删除不属于您的评论" }

        val now = LocalDateTime.now()
        commentsArea.delete = true
        commentsArea.deleteTime = now
        // 删除所有回复
        if (commentsArea.mainCommentId.isNullOrBlank()) {
            val subComments = commentsAreaKtormRepository.findByMainCommentId(commentsId)
            subComments.forEach { ca ->
                ca.deleteTime = now
                ca.delete = true
                commentsAreaKtormRepository.updateEntity(ca)
            }
        }
        commentsAreaKtormRepository.updateEntity(commentsArea)
        Cache.invalidateCommentCountById(commentsArea.copilotId)
    }

    /**
     * 为评论进行点赞
     *
     * @param userId            登录用户 id
     * @param commentsRatingDTO CommentsRatingDTO
     */
    fun rates(userId: String, commentsRatingDTO: CommentsRatingDTO) {
        val commentId = commentsRatingDTO.commentId
        val commentsArea = requireCommentsAreaById(commentId)

        val ratingChange = ratingService.rateComment(
            commentId,
            userId,
            RatingType.fromRatingType(commentsRatingDTO.rating),
        )
        // 更新评分后更新评论的点赞数
        val (likeCountChange, dislikeCountChange) = ratingService.calcLikeChange(ratingChange)

        // 点赞数不需要在高并发下特别精准，大概就行，但是也得避免特别离谱的数字
        commentsArea.likeCount = (commentsArea.likeCount + likeCountChange).coerceAtLeast(0)
        commentsArea.dislikeCount = (commentsArea.dislikeCount + dislikeCountChange).coerceAtLeast(0)

        commentsAreaKtormRepository.updateEntity(commentsArea)
    }

    /**
     * 评论置顶
     *
     * @param userId             登录用户 id
     * @param commentsToppingDTO CommentsToppingDTO
     */
    fun topping(userId: String, commentsToppingDTO: CommentsToppingDTO) {
        val commentsArea = requireCommentsAreaById(commentsToppingDTO.commentId)
        // 只允许作者置顶评论
        val copilot = copilotKtormRepository.findByCopilotId(commentsArea.copilotId)
        require(userId == copilot?.uploaderId) { "只有作者才能置顶评论" }

        commentsArea.topping = commentsToppingDTO.topping
        commentsAreaKtormRepository.updateEntity(commentsArea)
    }

    /**
     * 查询
     *
     * @param request CommentsQueriesDTO
     * @return CommentsAreaInfo
     */
    fun queriesCommentsArea(request: CommentsQueriesDTO): CommentsAreaInfo {
        val page = (request.page - 1).coerceAtLeast(0)
        val limit = if (request.limit > 0) request.limit else 10
        val pageable: Pageable = PageRequest.of(page, limit)

        // 主评论 - 使用Ktorm查询
        val mainCommentsPage = if (!request.justSeeId.isNullOrBlank()) {
            // 如果指定了评论ID，直接查询该评论
            val comment = commentsAreaKtormRepository.findById(request.justSeeId)
            if (comment != null && !comment.delete && comment.copilotId == request.copilotId && comment.mainCommentId.isNullOrBlank()) {
                org.springframework.data.domain.PageImpl(listOf(comment), pageable, 1)
            } else {
                org.springframework.data.domain.PageImpl(emptyList<CommentsAreaEntity>(), pageable, 0)
            }
        } else {
            commentsAreaKtormRepository.findByCopilotIdAndDeleteAndMainCommentIdExists(
                request.copilotId,
                delete = false,
                exists = false,
                pageable = pageable,
            )
        }

        val mainCommentIds = mainCommentsPage.content.mapNotNull { it.id }
        // 获取子评论
        val subCommentsList = if (mainCommentIds.isNotEmpty()) {
            commentsAreaKtormRepository.findByMainCommentIdIn(mainCommentIds).onEach {
                // 将已删除评论内容替换为空
                if (it.delete) it.message = ""
            }
        } else {
            emptyList()
        }

        // 获取所有评论用户
        val allUserIds = (mainCommentsPage.content + subCommentsList).map { it.uploaderId }.distinct()
        val users = userService.findByUsersId(allUserIds)
        val subCommentGroups = subCommentsList.groupBy { it.mainCommentId }

        // 转换主评论数据并填充用户名
        val commentsInfos = mainCommentsPage.content.map { mainComment ->
            val subCommentsInfos = (subCommentGroups[mainComment.id] ?: emptyList()).map { c ->
                buildSubCommentsInfo(c, users.getOrDefault(c.uploaderId))
            }
            buildMainCommentsInfo(mainComment, users.getOrDefault(mainComment.uploaderId), subCommentsInfos)
        }

        return CommentsAreaInfo(
            hasNext = mainCommentsPage.hasNext(),
            page = mainCommentsPage.totalPages,
            total = mainCommentsPage.totalElements,
            data = commentsInfos,
        )
    }

    /**
     * 转换子评论数据并填充用户名
     */
    private fun buildSubCommentsInfo(c: CommentsAreaEntity, user: MaaUser) = SubCommentsInfo(
        commentId = c.id,
        uploader = user.userName,
        uploaderId = c.uploaderId,
        message = c.message,
        uploadTime = c.uploadTime,
        like = c.likeCount,
        dislike = c.dislikeCount,
        fromCommentId = c.fromCommentId!!,
        mainCommentId = c.mainCommentId!!,
        deleted = c.delete,
    )

    private fun buildMainCommentsInfo(c: CommentsAreaEntity, user: MaaUser, subList: List<SubCommentsInfo>) = CommentsInfo(
        commentId = c.id,
        uploader = user.userName,
        uploaderId = c.uploaderId,
        message = c.message,
        uploadTime = c.uploadTime,
        like = c.likeCount,
        dislike = c.dislikeCount,
        topping = c.topping,
        subCommentsInfos = subList,
    )

    fun notificationStatus(userId: String, id: String, status: Boolean) {
        val commentsArea = requireCommentsAreaById(id)
        require(userId == commentsArea.uploaderId) { "您没有权限修改" }
        commentsArea.notification = status
        commentsAreaKtormRepository.updateEntity(commentsArea)
    }

    private fun requireCommentsAreaById(commentsId: String, lazyMessage: () -> Any = { "评论不存在" }): CommentsAreaEntity =
        commentsAreaKtormRepository.findById(commentsId)?.takeIf { !it.delete }.requireNotNull(lazyMessage)
}
