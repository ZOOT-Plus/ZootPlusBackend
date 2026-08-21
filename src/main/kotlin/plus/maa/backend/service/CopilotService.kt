package plus.maa.backend.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service
import plus.maa.backend.cache.transfer.CopilotInnerCacheInfo
import plus.maa.backend.common.Constants.COPILOT_VIEW_KEY
import plus.maa.backend.common.Constants.ME
import plus.maa.backend.common.Constants.VISITED_FLAG
import plus.maa.backend.common.extensions.blankAsNull
import plus.maa.backend.common.extensions.removeQuotes
import plus.maa.backend.common.extensions.requireNotNull
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.controller.request.copilot.CopilotCUDRequest
import plus.maa.backend.controller.request.copilot.CopilotContentDTO
import plus.maa.backend.controller.request.copilot.CopilotQueriesRequest
import plus.maa.backend.controller.request.copilot.CopilotRatingReq
import plus.maa.backend.controller.request.copilot.PrtsCUDRequest
import plus.maa.backend.controller.request.copilot.PrtsDTO
import plus.maa.backend.controller.request.copilot.VideoCUDRequest
import plus.maa.backend.controller.request.copilot.VideoDTO
import plus.maa.backend.controller.response.MaaResultException
import plus.maa.backend.controller.response.copilot.CopilotInfo
import plus.maa.backend.controller.response.copilot.CopilotPageInfo
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.Copilot
import plus.maa.backend.repository.entity.Copilot.OperationGroup
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.ktorm.CommentsAreaRepository
import plus.maa.backend.repository.ktorm.CopilotQueryRequest
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.UserRepository
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import plus.maa.backend.service.model.RatingType
import plus.maa.backend.service.sensitiveword.SensitiveWordService
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
import kotlin.math.max
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import plus.maa.backend.cache.InternalComposeCache as Cache

/**
 * @author LoMu
 * Date 2022-12-25 19:57
 */
@Service
class CopilotService(
    private val copilotRepository: CopilotRepository,
    private val ratingService: RatingService,
    private val json: Json,
    private val levelService: ArkLevelService,
    private val redisCache: RedisCache,
    private val userRepository: UserService,
    private val userRepo: UserRepository,
    private val commentsAreaRepository: CommentsAreaRepository,
    private val properties: MaaCopilotProperties,
    private val sensitiveWordService: SensitiveWordService,
    private val siteMessageService: SiteMessageService,
) {
    private val log = KotlinLogging.logger { }

    /**
     * 按请求类型解析作业内容，检验敏感词并修正前端的冗余部分。
     * 作业类型由请求子类型决定，不由内容 JSON 内字段决定。
     */
    private fun CopilotCUDRequest.parseContent(): CopilotContentDTO = try {
        when (this) {
            is PrtsCUDRequest -> json.decodeFromString<PrtsDTO>(content)
            is VideoCUDRequest -> json.decodeFromString<VideoDTO>(content)
        }
    } catch (e: Exception) {
        log.error(e) { "解析copilot失败" }
        throw MaaResultException("解析copilot失败")
    }.apply {
        sensitiveWordService.validate(doc)
        // 去除 name 的冗余部分
        groups?.forEach { group: Copilot.Groups ->
            group.opers?.forEach { oper: OperationGroup ->
                oper.name = oper.name?.removeQuotes()
            }
        }
        opers?.forEach { operator: Copilot.Operators ->
            operator.name = operator.name.removeQuotes()
        }
        // actions name 不是必须（仅 PRTS）
        if (this is PrtsDTO) {
            actions?.forEach { action: Copilot.Action ->
                action.name = action.name?.removeQuotes()
            }
        }
        // 使用 stageId 存储作业关卡信息
        levelService.findByLevelIdFuzzy(stageName)?.stageId?.let {
            stageName = it
        }
    }

    /** 请求类型对应的作业类型 */
    private fun CopilotCUDRequest.copilotType(): CopilotType = when (this) {
        is PrtsCUDRequest -> CopilotType.PRTS
        is VideoCUDRequest -> CopilotType.VIDEO
    }

    /** 从原始内容 JSON 中提取 video_url（PRTS 为 null） */
    private fun extractVideoUrl(content: String): String? = runCatching {
        json.parseToJsonElement(content).jsonObject["video_url"]?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * 上传新的作业
     */
    fun upload(loginUserId: Long, request: CopilotCUDRequest): Long {
        val dto = request.parseContent()
        val now = LocalDateTime.now()

        val entity = CopilotEntity(
            type = request.copilotType(),
            stageName = dto.stageName,
            uploaderId = loginUserId,
            views = 0L,
            ratingLevel = 0,
            ratingRatio = 0.0,
            likeCount = 0L,
            dislikeCount = 0L,
            hotScore = 0.0,
            title = dto.doc?.title ?: "",
            details = dto.doc?.details,
            firstUploadTime = now,
            uploadTime = now,
            content = request.content,
            status = request.status,
            commentStatus = CommentStatus.ENABLED,
            delete = false,
            deleteTime = null,
            notification = false,
        )
        copilotRepository.insertEntity(entity)
        val copilotId = entity.copilotId
        val opers = dto.opers
        if (!opers.isNullOrEmpty()) {
            copilotRepository.insertOperators(copilotId, opers.map { it.name })
        }
        if (request.status == CopilotSetStatus.PUBLIC) {
            try {
                siteMessageService.notifyCopilotPublished(loginUserId, copilotId, entity.title)
            } catch (e: Exception) {
                log.error(e) { "创建作业发布站内信失败, copilotId: $copilotId" }
            }
        }
        return copilotId
    }

    /**
     * 根据作业id删除作业
     */
    fun delete(loginUserId: Long, copilotIdToDelete: Long?) = userEditCopilot(loginUserId, copilotIdToDelete) {
        delete = true
        deleteTime = LocalDateTime.now()
    }.apply {
        // 删除作业时，如果被删除的项在 Redis 首页缓存中存在，则清空对应的首页缓存
        // 新增作业就不必，因为新作业显然不会那么快就登上热度榜和浏览量榜
        deleteCacheWhenMatchCopilotId(copilotId)
        Cache.invalidateCopilotInfoByCid(copilotId)
    }

    /**
     * 指定查询
     */
    fun getCopilotById(userIdOrIpAddress: String, id: Long): CopilotInfo? {
        val result = Cache.getCopilotCache(id) {
            copilotRepository.findNotDeletedCopilotId(id)?.run {
                CopilotInnerCacheInfo(this.copy())
            }
        }?.let {
            val copilot = it.info
            val maaUser = userRepository.findByUserIdOrDefaultInCache(copilot.uploaderId)

            val commentsCount = Cache.getCommentCountCache(copilot.copilotId) { cid ->
                commentsAreaRepository.countByCopilotId(cid, false)
            }
            // 查询个人评分（支持 userId 或 IP 地址）
            val personalRating = ratingService.findPersonalRatingOfCopilot(userIdOrIpAddress, id)
            copilot.format(
                personalRating,
                maaUser.userName,
                commentsCount,
            ) to it.view
        }

        return result?.apply {
            // 60分钟内限制同一个用户对访问量的增加
            val key = COPILOT_VIEW_KEY(id, userIdOrIpAddress)
            val visitResult = redisCache.setCacheIfAbsent(
                key,
                VISITED_FLAG,
                1.hours,
            )
            if (visitResult) {
                // 单机
                second.incrementAndGet()
                // 丢到调度队列中, 一致性要求不高
                Thread.startVirtualThread {
                    copilotRepository.incrViews(id)
                }
            }
        }?.run {
            first.copy(views = second.get())
        }
    }

    /**
     * 使用 postgresql 查询作业
     */
    fun queriesCopilot(userId: Long?, request: CopilotQueriesRequest): CopilotPageInfo {
        val cacheTimeout = AtomicLong()
        val cacheKey = AtomicReference<String?>()
        val setKey = AtomicReference<String>()
        // 只缓存默认状态下热度和访问量排序的结果，并且最多只缓存前三页
        val keyword = request.document?.trim()
        if (request.page <= 3 &&
            keyword.isNullOrEmpty() &&
            request.levelKeyword.isNullOrBlank() &&
            request.uploaderId.isNullOrBlank() &&
            request.operator.isNullOrBlank() &&
            request.copilotIds.isNullOrEmpty() &&
            request.type == null &&
            !request.onlyFollowing
        ) {
            request.orderBy?.blankAsNull()
                ?.let { key -> HOME_PAGE_CACHE_CONFIG[key] }
                ?.let { t ->
                    cacheTimeout.set(t)
                    setKey.set(String.format("home:%s:copilotIds", request.orderBy))
                    cacheKey.set(String.format("home:%s:%s", request.orderBy, request.hashCode()))
                    redisCache.getCache<CopilotPageInfo>(cacheKey.get()!!)
                }?.let { return it }
        }

        // 判断是否有值 无值则为默认
        val page = if (request.page > 0) request.page else 1
        val limit = if (request.limit > 0) request.limit else 10
        val levelKeyword = request.levelKeyword

        var inUserIds: List<Long>? = null

        val uploaderId = if (request.uploaderId == ME) {
            userId
        } else {
            request.uploaderId?.toLongOrNull()
        }
        uploaderId?.let {
            inUserIds = listOf(it)
        }

        var inCopilotIds: List<Long>? = request.copilotIds
        val documentKeyword = keyword?.takeIf { it.isNotEmpty() }

        val requestStatus = if (request.uploaderId == ME && userId != null) {
            request.status
        } else {
            CopilotSetStatus.PUBLIC
        }
        var stageNameKeyword: String? = null
        var stageNames: List<String>? = null
        if (levelKeyword != null) {
            val levelList = levelService.queryLevelInfosByKeyword(levelKeyword)
            if (levelList.isEmpty()) {
                stageNameKeyword = keyword
            } else {
                stageNames = levelList.map { level -> level.stageId }
            }
        }

        val ops = request.operator?.removeQuotes()?.split(",")?.filterNot(String::isBlank)
        var includeOps: List<String>? = null
        var notIncludeOps: List<String>? = null
        if (ops != null) {
            val g = ops.groupBy { it.startsWith('~') }
            if (!g[true].isNullOrEmpty()) {
                notIncludeOps = g[true]?.map { it.substring(1) }
            }
            if (!g[false].isNullOrEmpty()) {
                includeOps = g[false]
            }
        }

        val (copilots, count) = copilotRepository.queryCopilots(
            CopilotQueryRequest(
                type = request.type,
                status = requestStatus,
                stageNameKeyword = stageNameKeyword,
                stageNames = stageNames,
                documentKeyword = documentKeyword,
                inUserIds = inUserIds,
                inCopilotIds = inCopilotIds,
                onlyFollowingUserId = if (request.onlyFollowing) userId else null,
                includeOps = includeOps,
                notIncludeOps = notIncludeOps,
                orderBy = request.orderBy ?: "id",
                desc = request.desc,
                page = page,
                limit = limit,
            ),
        )

        val hasNext = if (keyword.isNullOrEmpty() &&
            request.levelKeyword.isNullOrBlank() &&
            request.uploaderId != null &&
            request.uploaderId != ME &&
            request.operator.isNullOrBlank() &&
            request.copilotIds.isNullOrEmpty()
        ) {
            // 聚合分支：hasNext 用总数判断
            count > (page * limit)
        } else {
            // 非聚合分支：hasNext 用当前页大小判断
            copilots.size >= limit
        }

        val userIds = copilots.map { it.uploaderId }

        // 填充前端所需信息
        val maaUsers = hashMapOf<Long, UserEntity>()
        val remainingUserIds = userIds.filter { userId ->
            val info = Cache.getMaaUserCache(userId.toString())?.also {
                maaUsers[userId] = it
            }
            info == null
        }.toList()
        if (remainingUserIds.isNotEmpty()) {
            val users = userRepo.findAllById(remainingUserIds)
            users.forEach {
                maaUsers[it.userId] = it
                Cache.setUserCache(it.userId.toString(), it)
            }
        }

        val copilotIds = copilots.map { it.copilotId }
        val commentsCount = hashMapOf<Long, Long>()
        val remainingCopilotIds = copilotIds.filter { copilotId ->
            val c = Cache.getCommentCountCache(copilotId)?.also {
                commentsCount[copilotId] = it
            }
            c == null
        }.toList()

        if (remainingCopilotIds.isNotEmpty()) {
            val existedCount = commentsAreaRepository.findByCopilotId(remainingCopilotIds, false)
                .groupBy { it.copilotId }
                .mapValues { it.value.size.toLong() }
            copilotIds.forEach { copilotId ->
                val count = existedCount[copilotId] ?: 0
                commentsCount[copilotId] = count
                Cache.setCommentCountCache(copilotId, count)
            }
        }

        // 新版评分系统
        // 反正目前首页和搜索不会直接展示当前用户有没有点赞，干脆直接不查，要用户点进作业才显示自己是否点赞
        val infos = copilots.map { copilot ->
            val contentObj = json.parseToJsonElement(copilot.content).jsonObject
            val slimContent = buildJsonObject {
                contentObj.forEach { (key, value) ->
                    if (key != "actions") put(key, value)
                }
            }
            copilot.content = json.encodeToString(slimContent)

            copilot.format(
                null,
                maaUsers.getOrDefault(copilot.uploaderId, UserEntity.UNKNOWN).userName,
                commentsCount[copilot.copilotId] ?: 0,
            )
        }

        // 封装数据
        val data = CopilotPageInfo(hasNext, page, count, infos)

        // 决定是否缓存
        if (cacheKey.get() != null) {
            // 记录存在的作业id
            redisCache.addSet(setKey.get(), copilotIds, cacheTimeout.get().seconds)
            // 缓存数据
            redisCache.setCache(cacheKey.get()!!, data, cacheTimeout.get().seconds)
        }
        return data
    }

    /**
     * 增量更新。作业类型不可更改，请求类型必须与已存储类型一致，否则 400。
     */
    fun update(loginUserId: Long, request: CopilotCUDRequest) {
        var cIdToDeleteCache: Long? = null

        val dto = request.parseContent()
        val requestType = request.copilotType()
        userEditCopilot(loginUserId, request.id) {
            // 作业类型不可更改
            if (type != requestType) {
                throw MaaResultException(400, "作业类型不可更改")
            }

            // 从公开改为隐藏时，如果数据存在缓存中则需要清除缓存
            if (status == CopilotSetStatus.PUBLIC && request.status == CopilotSetStatus.PRIVATE) cIdToDeleteCache = copilotId

            stageName = dto.stageName
            title = dto.doc?.title ?: title
            details = dto.doc?.details ?: details
            content = request.content
            status = request.status
            uploadTime = LocalDateTime.now()
        }.apply {
            Cache.invalidateCopilotInfoByCid(copilotId)
            copilotRepository.replaceOperators(copilotId, dto.opers?.map { it.name } ?: emptyList())
        }

        cIdToDeleteCache?.let {
            deleteCacheWhenMatchCopilotId(it)
        }
    }

    /**
     * 评分相关
     *
     * @param request           评分
     * @param userIdOrIpAddress 用于已登录用户作出评分
     */
    fun rates(userIdOrIpAddress: String, request: CopilotRatingReq) {
        requireNotNull(copilotRepository.existsByCopilotId(request.id)) { "作业id不存在" }

        // 使用 userIdOrIpAddress 进行评分（支持登录用户的 userId 或未登录用户的 IP 地址）
        val ratingChange = ratingService.rateCopilot(
            request.id,
            userIdOrIpAddress,
            RatingType.fromRatingType(request.rating),
        )
        val (likeCountChange, dislikeCountChange) = ratingService.calcLikeChange(ratingChange)

        // 获取作业
        val copilot = copilotRepository.findNotDeletedCopilotId(request.id)
        checkNotNull(copilot) { "作业不存在" }

        // 计算评分相关
        val likeCount = (copilot.likeCount + likeCountChange).coerceAtLeast(0)
        val ratingCount = (likeCount + copilot.dislikeCount + dislikeCountChange).coerceAtLeast(0)

        val rawRatingLevel = if (ratingCount != 0L) likeCount.toDouble() / ratingCount else 0.0
        // 只取一位小数点
        val ratingLevel = rawRatingLevel.toBigDecimal().setScale(1, RoundingMode.HALF_UP).toDouble()
        // 更新数据
        copilot.likeCount = likeCount
        copilot.dislikeCount = ratingCount - likeCount
        copilot.ratingLevel = (ratingLevel * 10).toInt()
        copilot.ratingRatio = ratingLevel
        copilotRepository.updateEntity(copilot)

        // 记录近期评分变化量前 100 的作业 id
        redisCache.incZSet(
            "rate:hot:copilotIds",
            request.id.toString(),
            1.0,
            100,
            (3600 * 3).toLong(),
        )
    }

    private fun CopilotEntity.format(rating: RatingEntity?, userName: String, commentsCount: Long) = CopilotInfo(
        id = copilotId,
        type = type,
        videoUrl = if (type == CopilotType.VIDEO) extractVideoUrl(content) else null,
        uploadTime = uploadTime,
        uploaderId = uploaderId.toString(),
        uploader = userName,
        views = views,
        hotScore = hotScore,
        available = true,
        ratingLevel = ratingLevel,
        notEnoughRating = likeCount + dislikeCount <= this@CopilotService.properties.copilot.minValueShowNotEnoughRating,
        ratingRatio = ratingRatio,
        ratingType = (rating?.rating ?: RatingType.NONE).display,
        commentsCount = commentsCount,
        commentStatus = commentStatus,
        content = content,
        like = likeCount,
        dislike = dislikeCount,
        status = status,
    )

    fun notificationStatus(userId: Long, copilotId: Long, status: Boolean) = userEditCopilot(userId, copilotId) {
        notification = status
    }

    fun commentStatus(userId: Long, copilotId: Long, status: CommentStatus) = userEditCopilot(userId, copilotId) {
        commentStatus = status
    }

    fun userEditCopilot(userId: Long?, copilotId: Long?, edit: CopilotEntity.() -> Unit): CopilotEntity {
        val cId = copilotId.requireNotNull { "copilotId 不能为空" }
        val copilot = copilotRepository.findNotDeletedCopilotId(cId).requireNotNull { "copilot 不存在" }
        require(copilot.uploaderId == userId) { "您没有权限修改" }
        copilot.apply(edit)
        copilotRepository.updateEntity(copilot)
        return copilot
    }

    /**
     * 用于重置缓存，数据修改为私有或者删除时用于重置缓存防止继续被查询到
     */
    private fun deleteCacheWhenMatchCopilotId(copilotId: Long) {
        for (k in HOME_PAGE_CACHE_CONFIG.keys) {
            val key = String.format("home:%s:copilotIds", k)
            val pattern = String.format("home:%s:*", k)
            if (redisCache.valueMemberInSet(key, copilotId)) {
                redisCache.removeCacheByPattern(pattern)
            }
        }
    }

    companion object {

        /**
         * 首页分页查询缓存配置
         * 格式为：需要缓存的 orderBy 类型（也就是榜单类型） -> 缓存时间
         * （[mapOf]返回的是不可变对象，无需担心线程安全问题）
         */
        private val HOME_PAGE_CACHE_CONFIG = mapOf(
            "hot" to 3600 * 24L,
            "views" to 3600L,
            "id" to 300L,
        )

        @JvmStatic
        fun getHotScore(copilot: CopilotEntity, lastWeekLike: Long, lastWeekDislike: Long): Double {
            val now = LocalDateTime.now()
            val uploadTime = copilot.uploadTime
            // 基于时间的基础分
            var base = 6.0
            // 相比上传时间过了多少周
            val pastedWeeks = ChronoUnit.WEEKS.between(uploadTime, now) + 1
            base /= ln((pastedWeeks + 1).toDouble())
            // 上一周好评率
            val ups = max(lastWeekLike.toDouble(), 1.0).toLong()
            val downs = max(lastWeekDislike.toDouble(), 0.0).toLong()
            val greatRate = ups.toDouble() / (ups + downs)
            if ((ups + downs) >= 5 && downs >= ups) {
                // 差评过多的作业分数稀释
                base *= greatRate
            }
            // 上一周好评率 * (上一周评分数 / 10) * (浏览数 / 10) / 过去的周数
            val s = (greatRate * (copilot.views / 10.0) * max((ups + downs) / 10.0, 1.0)) / pastedWeeks
            val order = ln(max(s, 1.0))
            return order + s / 1000.0 + base
        }
    }
}
