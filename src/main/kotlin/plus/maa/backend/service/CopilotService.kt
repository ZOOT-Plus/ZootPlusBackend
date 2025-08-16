package plus.maa.backend.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.asc
import org.ktorm.dsl.desc
import org.ktorm.dsl.eq
import org.ktorm.dsl.from
import org.ktorm.dsl.inList
import org.ktorm.dsl.like
import org.ktorm.dsl.notInList
import org.ktorm.dsl.select
import org.ktorm.dsl.where
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.forEach
import org.ktorm.entity.sortedBy
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.ktorm.expression.ArgumentExpression
import org.ktorm.schema.BooleanSqlType
import org.ktorm.schema.ColumnDeclaring
import org.springframework.stereotype.Service
import plus.maa.backend.cache.transfer.CopilotInnerCacheInfo
import plus.maa.backend.common.extensions.blankAsNull
import plus.maa.backend.common.extensions.removeQuotes
import plus.maa.backend.common.extensions.requireNotNull
import plus.maa.backend.common.utils.IdComponent
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.controller.request.copilot.CopilotCUDRequest
import plus.maa.backend.controller.request.copilot.CopilotDTO
import plus.maa.backend.controller.request.copilot.CopilotQueriesRequest
import plus.maa.backend.controller.request.copilot.CopilotRatingReq
import plus.maa.backend.controller.response.MaaResultException
import plus.maa.backend.controller.response.copilot.CopilotInfo
import plus.maa.backend.controller.response.copilot.CopilotPageInfo
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.Copilot
import plus.maa.backend.repository.entity.Copilot.OperationGroup
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Operators
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.copilots
import plus.maa.backend.repository.entity.users
import plus.maa.backend.repository.ktorm.CommentsAreaKtormRepository
import plus.maa.backend.repository.ktorm.CopilotKtormRepository
import plus.maa.backend.repository.ktorm.UserFollowingKtormRepository
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.RatingType
import plus.maa.backend.service.segment.SegmentService
import plus.maa.backend.service.sensitiveword.SensitiveWordService
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
import kotlin.math.max
import plus.maa.backend.cache.InternalComposeCache as Cache

/**
 * @author LoMu
 * Date 2022-12-25 19:57
 */
@Service
class CopilotService(
    private val database: Database,
    private val copilotKtormRepository: CopilotKtormRepository,
    private val ratingService: RatingService,
    private val mapper: ObjectMapper,
    private val levelService: ArkLevelService,
    private val redisCache: RedisCache,
    private val idComponent: IdComponent,
    private val userRepository: UserService,
    private val commentsAreaKtormRepository: CommentsAreaKtormRepository,
    private val properties: MaaCopilotProperties,
    private val sensitiveWordService: SensitiveWordService,
    private val segmentService: SegmentService,
    private val userFollowingKtormRepository: UserFollowingKtormRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger { }

    /**
     * 将字符串解析为 [CopilotDTO], 检验敏感词并修正前端的冗余部分
     */
    private fun String.parseToCopilotDto() = try {
        mapper.readValue(this, CopilotDTO::class.java)
    } catch (e: JsonProcessingException) {
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
            operator.name = operator.name?.removeQuotes()
        }
        // actions name 不是必须
        actions?.forEach { action: Copilot.Action ->
            action.name = action.name?.removeQuotes()
        }
        // 使用 stageId 存储作业关卡信息
        levelService.findByLevelIdFuzzy(stageName)?.stageId?.let {
            stageName = it
        }
    }

    /**
     * 上传新的作业
     */
    fun upload(loginUserId: String, request: CopilotCUDRequest): Long {
        val dto = request.content.parseToCopilotDto()
        val copilotId = idComponent.getId(Copilot.META)
        val now = LocalDateTime.now()

        val entity = CopilotEntity {
            this.copilotId = copilotId
            this.stageName = dto.stageName
            this.uploaderId = loginUserId
            this.views = 0L
            this.ratingLevel = 0
            this.ratingRatio = 0.0
            this.likeCount = 0L
            this.dislikeCount = 0L
            this.hotScore = 0.0
            this.title = dto.doc?.title ?: ""
            this.details = dto.doc?.details
            this.firstUploadTime = now
            this.uploadTime = now
            this.content = request.content
            this.status = request.status
            this.commentStatus = CommentStatus.ENABLED
            this.delete = false
            this.deleteTime = null
            this.notification = false
        }

        copilotKtormRepository.insertEntity(entity)
        segmentService.updateIndex(copilotId, entity.title, entity.details)
        return copilotId
    }

    /**
     * 根据作业id删除作业
     */
    fun delete(loginUserId: String, request: CopilotCUDRequest) = userEditCopilot(loginUserId, request.id) {
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
            database.copilots.filter { copilot ->
                (copilot.copilotId eq id) and (copilot.delete eq false)
            }.firstOrNull()?.run {
                CopilotInnerCacheInfo(this.copy())
            }
        }?.let {
            val copilot = it.info
            val maaUser = userRepository.findByUserIdOrDefaultInCache(copilot.uploaderId)

            val commentsCount = Cache.getCommentCountCache(copilot.copilotId) { cid ->
                commentsAreaKtormRepository.countByCopilotIdAndDelete(cid, false)
            }
            copilot.format(
                ratingService.findPersonalRatingOfCopilot(userIdOrIpAddress, id),
                maaUser.userName,
                commentsCount,
            ) to it.view
        }

        return result?.apply {
            // 60分钟内限制同一个用户对访问量的增加
            val viewCacheKey = "views:$id:$userIdOrIpAddress"
            val visitResult = redisCache.setCacheIfAbsent(
                viewCacheKey,
                VISITED_FLAG,
                1,
                TimeUnit.HOURS,
            )
            if (visitResult) {
                // 单机
                second.incrementAndGet()
                // 丢到调度队列中, 一致性要求不高
                Thread.startVirtualThread {
                    copilotKtormRepository.findByCopilotIdAndDeleteIsFalse(id)?.let { copilot ->
                        copilot.views += 1
                        copilotKtormRepository.updateEntity(copilot)
                    }
                }
            }
        }?.run {
            first.copy(views = second.get())
        }
    }

    /**
     * 使用 postgresql 查询作业
     */
    fun queriesCopilot(userId: String?, request: CopilotQueriesRequest): CopilotPageInfo {
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
            !request.onlyFollowing
        ) {
            request.orderBy?.blankAsNull()
                ?.let { key -> HOME_PAGE_CACHE_CONFIG[key] }
                ?.let { t ->
                    cacheTimeout.set(t)
                    setKey.set(String.format("home:%s:copilotIds", request.orderBy))
                    cacheKey.set(String.format("home:%s:%s", request.orderBy, request.hashCode()))
                    redisCache.getCache(cacheKey.get()!!, CopilotPageInfo::class.java)
                }?.let { return it }
        }

        // 判断是否有值 无值则为默认
        val page = if (request.page > 0) request.page else 1
        val limit = if (request.limit > 0) request.limit else 10
        val levelKeyword = request.levelKeyword

        var inUserIds: List<String>? = null
        if (request.onlyFollowing && userId != null) {
            val followingIds = userFollowingKtormRepository.getFollowingIds(userId)
            if (followingIds.isEmpty()) {
                return CopilotPageInfo(false, 0, 0, emptyList())
            }
            // 添加查询范围为关注者
            inUserIds = followingIds
        }

        val uploaderId = if (request.uploaderId == "me") userId else request.uploaderId
        uploaderId?.blankAsNull()?.let {
            inUserIds = listOf(it)
        }

        var inCopilotIds: List<Long>? = request.copilotIds
        if (!(keyword?.length == 1 && keyword[0].isLetterOrDigit())) {
            segmentService.getSegment(keyword)
                .takeIf {
                    it.isNotEmpty()
                }
                ?.let { words ->
                    val idList = words.mapNotNull {
                        val result = segmentService.fetchIndexInfo(it)
                        if (it.equals(keyword, ignoreCase = true) && result.isEmpty()) {
                            null
                        } else {
                            result
                        }
                    }

                    val intersection = when {
                        idList.isEmpty() -> emptySet()
                        else -> {
                            val iterator = idList.iterator()
                            val result = HashSet(iterator.next())
                            while (iterator.hasNext()) {
                                result.retainAll(iterator.next())
                            }
                            result
                        }
                    }

                    if (intersection.isEmpty()) {
                        return CopilotPageInfo(false, 1, 0, emptyList())
                    }
                    inCopilotIds = inCopilotIds?.intersect(intersection)?.toList() ?: intersection.toList()
                }
        }

        val requestStatus = if (request.uploaderId == "me" && userId != null) {
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

        val copilotsSeq = database.copilots.filter {
            val conditions = ArrayList<ColumnDeclaring<Boolean>>()
            conditions += ArgumentExpression(true, BooleanSqlType)
            conditions += it.delete eq false
            if (requestStatus != null) {
                conditions += it.status eq requestStatus
            }
            if (stageNameKeyword != null) {
                conditions += it.stageName like stageNameKeyword
            }
            if (stageNames != null) {
                conditions += it.stageName inList stageNames
            }
            if (inUserIds != null) {
                conditions += it.uploaderId inList inUserIds
            }
            if (inCopilotIds != null) {
                conditions += it.copilotId inList inCopilotIds
            }
            if (includeOps != null) {
                conditions += it.copilotId inList (
                    database.from(Operators)
                        .select(Operators.copilotId)
                        .where { Operators.name inList includeOps }
                    )
            }
            if (notIncludeOps != null) {
                conditions += it.copilotId notInList (
                    database.from(Operators)
                        .select(Operators.copilotId)
                        .where { Operators.name inList notIncludeOps }
                    )
            }
            conditions.reduce { a, b -> a and b }
        }.sortedBy {
            val ord = when (request.orderBy ?: "id") {
                "hot" -> it.hotScore
                "id" -> it.copilotId
                "views" -> it.views
                else -> it.copilotId
            }
            if (request.desc) {
                ord.desc()
            } else {
                ord.asc()
            }
        }.drop((page - 1) * limit).take(limit)

        val resultAgg = if (keyword.isNullOrEmpty() &&
            request.levelKeyword.isNullOrBlank() &&
            request.uploaderId != null &&
            request.uploaderId != "me" &&
            request.operator.isNullOrBlank() &&
            request.copilotIds.isNullOrEmpty()
        ) {
            val r = copilotsSeq.toList()
            val count = r.count()
            val hasNext = count > (page * limit)
            (r to count) to hasNext
        } else {
            val r = copilotsSeq.toList()
            (r to 0) to (r.size >= limit)
        }

        val count = resultAgg.first.second
        val copilots: List<CopilotEntity> = resultAgg.first.first
        val hasNext = resultAgg.second

        val userIds = copilots.map { it.uploaderId }

        // 填充前端所需信息
        val maaUsers = hashMapOf<String, UserEntity>()
        val remainingUserIds = userIds.filter { userId ->
            val info = Cache.getMaaUserCache(userId)?.also {
                maaUsers[userId] = it
            }
            info == null
        }.toList()
        if (remainingUserIds.isNotEmpty()) {
            val users = database.users.filter { it.userId inList remainingUserIds }
            users.forEach {
                maaUsers[it.userId] = it
                Cache.setUserCache(it.userId, it)
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
            val existedCount = commentsAreaKtormRepository.findByCopilotIdInAndDelete(remainingCopilotIds, false)
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
            val contentObj = objectMapper.readTree(copilot.content) as ObjectNode
            contentObj.remove("actions")
            contentObj.remove("minimum_required")
            contentObj.remove("stage_name")
            copilot.content = contentObj.toString()

            copilot.format(
                null,
                maaUsers.getOrDefault(copilot.uploaderId, UserEntity.UNKNOWN).userName,
                commentsCount[copilot.copilotId] ?: 0,
            )
        }

        // 封装数据
        val data = CopilotPageInfo(hasNext, page, count.toLong(), infos)

        // 决定是否缓存
        if (cacheKey.get() != null) {
            // 记录存在的作业id
            redisCache.addSet(setKey.get(), copilotIds, cacheTimeout.get())
            // 缓存数据
            redisCache.setCache(cacheKey.get()!!, data, cacheTimeout.get())
        }
        return data
    }

    /**
     * 增量更新
     */
    fun update(loginUserId: String, request: CopilotCUDRequest) {
        var cIdToDeleteCache: Long? = null

        userEditCopilot(loginUserId, request.id) {
            segmentService.removeIndex(copilotId, title, details)

            // 从公开改为隐藏时，如果数据存在缓存中则需要清除缓存
            if (status == CopilotSetStatus.PUBLIC && request.status == CopilotSetStatus.PRIVATE) cIdToDeleteCache = copilotId

            val dto = request.content.parseToCopilotDto()
            stageName = dto.stageName
            title = dto.doc?.title ?: title
            details = dto.doc?.details ?: details
            content = request.content
            status = request.status
            uploadTime = LocalDateTime.now()
        }.apply {
            Cache.invalidateCopilotInfoByCid(copilotId)
            segmentService.updateIndex(copilotId, title, details)
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
        requireNotNull(copilotKtormRepository.existsByCopilotId(request.id)) { "作业id不存在" }

        val ratingChange = ratingService.rateCopilot(
            request.id,
            userIdOrIpAddress,
            RatingType.fromRatingType(request.rating),
        )
        val (likeCountChange, dislikeCountChange) = ratingService.calcLikeChange(ratingChange)

        // 获取作业
        val copilot = copilotKtormRepository.findByCopilotIdAndDeleteIsFalse(request.id)
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
        copilotKtormRepository.updateEntity(copilot)

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
        uploadTime = uploadTime,
        uploaderId = uploaderId,
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

    fun notificationStatus(userId: String, copilotId: Long, status: Boolean) = userEditCopilot(userId, copilotId) {
        notification = status
    }

    fun commentStatus(userId: String, copilotId: Long, status: CommentStatus) = userEditCopilot(userId, copilotId) {
        commentStatus = status
    }

    fun userEditCopilot(userId: String?, copilotId: Long?, edit: CopilotEntity.() -> Unit): CopilotEntity {
        val cId = copilotId.requireNotNull { "copilotId 不能为空" }
        val copilot = copilotKtormRepository.findByCopilotIdAndDeleteIsFalse(cId).requireNotNull { "copilot 不存在" }
        require(copilot.uploaderId == userId) { "您没有权限修改" }
        copilot.apply(edit)
        copilotKtormRepository.updateEntity(copilot)
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

        private const val VISITED_FLAG = "1"

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
