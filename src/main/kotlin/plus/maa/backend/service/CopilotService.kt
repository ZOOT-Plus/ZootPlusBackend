package plus.maa.backend.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.inValues
import org.springframework.stereotype.Service
import plus.maa.backend.cache.transfer.CopilotInnerCacheInfo
import plus.maa.backend.common.extensions.blankAsNull
import plus.maa.backend.common.extensions.removeQuotes
import plus.maa.backend.common.extensions.requireNotNull
import plus.maa.backend.common.utils.IdComponent
import plus.maa.backend.common.utils.converter.CopilotConverter
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.controller.request.copilot.CopilotCUDRequest
import plus.maa.backend.controller.request.copilot.CopilotDTO
import plus.maa.backend.controller.request.copilot.CopilotQueriesRequest
import plus.maa.backend.controller.request.copilot.CopilotRatingReq
import plus.maa.backend.controller.response.MaaResultException
import plus.maa.backend.controller.response.copilot.ArkLevelInfo
import plus.maa.backend.controller.response.copilot.CopilotInfo
import plus.maa.backend.controller.response.copilot.CopilotPageInfo
import plus.maa.backend.repository.CommentsAreaRepository
import plus.maa.backend.repository.CopilotRepository
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.UserFollowingRepository
import plus.maa.backend.repository.entity.Copilot
import plus.maa.backend.repository.entity.Copilot.OperationGroup
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.UserEntity
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
import java.util.regex.Pattern
import kotlin.math.ln
import kotlin.math.max
import plus.maa.backend.cache.InternalComposeCache as Cache

/**
 * @author LoMu
 * Date 2022-12-25 19:57
 */
@Service
class CopilotService(
    private val sqlClient: KSqlClient,
    private val copilotRepository: CopilotRepository,
    private val ratingService: RatingService,
    private val mongoTemplate: MongoTemplate,
    private val mapper: ObjectMapper,
    private val levelService: ArkLevelService,
    private val redisCache: RedisCache,
    private val idComponent: IdComponent,
    private val userRepository: UserService,
    private val commentsAreaRepository: CommentsAreaRepository,
    private val properties: MaaCopilotProperties,
    private val copilotConverter: CopilotConverter,
    private val sensitiveWordService: SensitiveWordService,
    private val segmentService: SegmentService,
    private val userFollowingRepository: UserFollowingRepository,
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
    fun upload(loginUserId: String, request: CopilotCUDRequest): Long = copilotConverter.toCopilot(
        request.content.parseToCopilotDto(),
        idComponent.getId(Copilot.META),
        loginUserId,
        LocalDateTime.now(),
        request.content,
        request.status,
    ).run {
        copilotRepository.insert(this).copilotId!!.also {
            segmentService.updateIndex(it, doc?.title, doc?.details)
        }
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
        deleteCacheWhenMatchCopilotId(copilotId!!)
        Cache.invalidateCopilotInfoByCid(copilotId)
    }

    /**
     * 指定查询
     */
    fun getCopilotById(userIdOrIpAddress: String, id: Long): CopilotInfo? {
        val result = Cache.getCopilotCache(id) {
            copilotRepository.findByCopilotIdAndDeleteIsFalse(it)?.run {
                CopilotInnerCacheInfo(this)
            }
        }?.let { it ->
            val copilot = it.info
            val maaUser = userRepository.findByUserIdOrDefaultInCache(copilot.uploaderId!!)

            val commentsCount = Cache.getCommentCountCache(copilot.copilotId!!) { cid ->
                commentsAreaRepository.countByCopilotIdAndDelete(cid, false)
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
                    val query = Query.query(Criteria.where("copilotId").`is`(id))
                    val update = Update().apply {
                        inc("views")
                    }
                    mongoTemplate.updateFirst(query, update, Copilot::class.java)
                }
            }
        }?.run {
            first.copy(views = second.get())
        }
    }

    /**
     * 分页查询。传入 userId 不为空时限制为用户所有的数据
     * 会缓存默认状态下热度和访问量排序的结果
     *
     * @param userId  获取已登录用户自己的作业数据
     * @param request 模糊查询
     * @return CopilotPageInfo
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

        val sortOrder = Sort.Order(
            if (request.desc) Sort.Direction.DESC else Sort.Direction.ASC,
            request.orderBy?.blankAsNull().let { ob ->
                when (ob) {
                    "hot" -> "hotScore"
                    "id" -> "copilotId"
                    else -> request.orderBy
                }
            } ?: "copilotId",
        )
        // 判断是否有值 无值则为默认
        val page = if (request.page > 0) request.page else 1
        val limit = if (request.limit > 0) request.limit else 10

        val pageable: Pageable = PageRequest.of(page - 1, limit, Sort.by(sortOrder))

        val criteriaObj = Criteria()

        val andQueries: MutableSet<Criteria> = HashSet()
        val norQueries: MutableSet<Criteria> = HashSet()
        val orQueries: MutableSet<Criteria> = HashSet()

        andQueries.add(Criteria.where("delete").`is`(false))

        if (request.onlyFollowing && userId != null) {
            val userFollowing = userFollowingRepository.findByUserId(userId)
            val followingIds = userFollowing?.followList ?: emptyList()

            if (followingIds.isEmpty()) {
                return CopilotPageInfo(false, 0, 0, emptyList())
            }
            // 添加查询范围为关注者
            andQueries.add(Criteria.where("uploaderId").`in`(followingIds))
        }
        // 仅查询自己的作业时才展示所有数据，否则只查询公开作业
        if (request.uploaderId == "me" && userId != null) {
            if (request.status != null) {
                andQueries.add(Criteria.where("status").`is`(request.status))
            }
        } else {
            andQueries.add(Criteria.where("status").`is`(CopilotSetStatus.PUBLIC))
        }

        // 关卡名、关卡类型、关卡编号
        request.levelKeyword?.blankAsNull()?.let { keyword ->
            val levelInfo = levelService.queryLevelInfosByKeyword(keyword)
            val c = if (levelInfo.isEmpty()) {
                Criteria.where("stageName").regex(keyword.toPattern(Pattern.CASE_INSENSITIVE))
            } else {
                Criteria.where("stageName").`in`(levelInfo.map(ArkLevelInfo::stageId))
            }
            andQueries.add(c)
        }

        // 作业id列表
        request.copilotIds?.ifEmpty { null }?.let { ids ->
            andQueries.add(Criteria.where("copilotId").`in`(ids))
        }

        // 包含或排除干员
        request.operator?.removeQuotes()?.split(",")?.filterNot(String::isBlank)?.forEach { oper ->
            if (oper.startsWith("~")) {
                // 排除查询指定干员
                norQueries.add(Criteria.where("opers.name").`is`(oper.substring(1)))
            } else {
                // 模糊匹配查询指定干员
                andQueries.add(Criteria.where("opers.name").`is`(oper))
            }
        }

        val uploaderId = if (request.uploaderId == "me") userId else request.uploaderId
        uploaderId?.blankAsNull()?.let {
            andQueries.add(Criteria.where("uploaderId").`is`(it))
        }

        // 封装查询
        if (andQueries.isNotEmpty()) {
            criteriaObj.andOperator(andQueries)
        }
        if (norQueries.isNotEmpty()) {
            criteriaObj.norOperator(norQueries)
        }
        if (orQueries.isNotEmpty()) {
            criteriaObj.orOperator(orQueries)
        }

        // 标题、描述、神秘代码
        val queryObj = Query().addCriteria(criteriaObj)

        if (!(keyword?.length == 1 && keyword[0].isLetterOrDigit())) {
            segmentService.getSegment(keyword)
                .takeIf {
                    it.isNotEmpty()
                }
                ?.let { words ->
                    val idList = words.mapNotNull {
                        val result = segmentService.fetchIndexInfo(it)
                        if (it.lowercase() == keyword?.lowercase() && result.isEmpty()) {
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
                    queryObj.addCriteria(Copilot::copilotId inValues intersection)
                }
        }

        // 去除large fields
        queryObj.fields().exclude("content", "actions")

        val countQueryObj = Query.of(queryObj)
        // 分页排序查询
        val copilots = mongoTemplate.find(queryObj.with(pageable), Copilot::class.java)

        val userIds = copilots.mapNotNull { it.uploaderId }

        // 填充前端所需信息
        val maaUsers = hashMapOf<String, MaaUser>()
        val remainingUserIds = userIds.filter { userId ->
            val info = Cache.getMaaUserCache(userId)?.also {
                maaUsers[userId] = it
            }
            info == null
        }.toList()
        if (remainingUserIds.isNotEmpty()) {
            userRepository.findByUsersId(remainingUserIds).entries().forEach {
                maaUsers.put(it.key, it.value)
                Cache.setMaaUserCache(it.key, it.value)
            }
        }

        val copilotIds = copilots.mapNotNull { it.copilotId }
        val commentsCount = hashMapOf<Long, Long>()
        val remainingCopilotIds = copilotIds.filter { copilotId ->
            val c = Cache.getCommentCountCache(copilotId)?.also {
                commentsCount[copilotId] = it
            }
            c == null
        }.toList()

        if (remainingCopilotIds.isNotEmpty()) {
            val existedCount = commentsAreaRepository.findByCopilotIdInAndDelete(remainingCopilotIds, false)
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
            copilot.content = mapOf(
                "stageName" to copilot.stageName,
                "doc" to copilot.doc,
                "opers" to copilot.opers,
                "groups" to copilot.groups,
                "minimumRequired" to copilot.minimumRequired,
                "difficulty" to copilot.difficulty,
            ).run(mapper::writeValueAsString)
            copilot.format(
                null,
                maaUsers.getOrDefault(copilot.uploaderId!!, MaaUser.UNKNOWN).userName,
                commentsCount[copilot.copilotId] ?: 0,
            )
        }

        // 作者页需要返回作业数目
        val (count, hasNext) = if (keyword.isNullOrEmpty() &&
            request.levelKeyword.isNullOrBlank() &&
            request.uploaderId != null &&
            request.uploaderId != "me" &&
            request.operator.isNullOrBlank() &&
            request.copilotIds.isNullOrEmpty()
        ) {
            // 查询总数
            val count = mongoTemplate.count(countQueryObj, Copilot::class.java)
            val hasNext = count.toInt() > (page * limit)
            count to hasNext
        } else {
            0L to (infos.size >= limit)
        }

        // 封装数据
        val data = CopilotPageInfo(hasNext, page, count, infos)

        // 决定是否缓存
        if (cacheKey.get() != null) {
            // 记录存在的作业id
            redisCache.addSet(setKey.get(), copilotIds, cacheTimeout.get())
            // 缓存数据
            redisCache.setCache(cacheKey.get()!!, data, cacheTimeout.get())
        }
        return data
    }


    fun queriesCopilotPG(userId: String?, request: CopilotQueriesRequest): CopilotPageInfo {
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
            val userFollowing = userFollowingRepository.findByUserId(userId)
            val followingIds = userFollowing?.followList ?: emptyList()
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
                        if (it.lowercase() == keyword?.lowercase() && result.isEmpty()) {
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

//        val copilotsQuery = CopilotEntity().select().page(page, limit)
//            .where {
//                it.uploaderId in inUserIds
//                    && it.stageName like stageNameKeyword
//                    && it.stageName in stageNames
//                    && it.copilotId in inCopilotIds
//                    && it.status == requestStatus
//            }
//            .orderBy {
//                val ord = when (request.orderBy ?: "id") {
//                    "hot" -> it.hotScore
//                    "id" -> it.copilotId
//                    "views" -> it.views
//                    else -> it.copilotId
//                }
//                if (request.desc) ord.desc()
//                else ord.asc()
//            }
//
//        val resultAgg = if (keyword.isNullOrEmpty() &&
//            request.levelKeyword.isNullOrBlank() &&
//            request.uploaderId != null &&
//            request.uploaderId != "me" &&
//            request.operator.isNullOrBlank() &&
//            request.copilotIds.isNullOrEmpty()
//        ) {
//            val r = copilotsQuery.withTotal().queryList()
//            val hasNext = r.first > (page * limit)
//            r to hasNext
//        } else {
//            val r = copilotsQuery.queryList()
//            (0 to r) to (r.size >= limit)
//        }
        // TODO 包含或排除干员
//        request.operator?.removeQuotes()?.split(",")?.filterNot(String::isBlank)?.forEach { oper ->
//            if (oper.startsWith("~")) {
//                // 排除查询指定干员
//                norQueries.add(Criteria.where("opers.name").`is`(oper.substring(1)))
//            } else {
//                // 模糊匹配查询指定干员
//                andQueries.add(Criteria.where("opers.name").`is`(oper))
//            }
//        }


        val count = 0
        val copilots: List<CopilotEntity> = emptyList()
        val hasNext = false

        val userIds = copilots.map { it.uploaderId }

        // 填充前端所需信息
        val maaUsers = hashMapOf<String, UserEntity>()
        val remainingUserIds = userIds.filter { userId ->
            val info = Cache.getUserCache(userId)?.also {
                maaUsers[userId] = it
            }
            info == null
        }.toList()
        if (remainingUserIds.isNotEmpty()) {
            val users = sqlClient.findByIds(UserEntity::class, remainingUserIds)
            users.forEach {
                maaUsers.put(it.userId, it)
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
            val existedCount = commentsAreaRepository.findByCopilotIdInAndDelete(remainingCopilotIds, false)
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
            segmentService.removeIndex(copilotId!!, doc?.title, doc?.details)

            // 从公开改为隐藏时，如果数据存在缓存中则需要清除缓存
            if (status == CopilotSetStatus.PUBLIC && request.status == CopilotSetStatus.PRIVATE) cIdToDeleteCache = copilotId
            copilotConverter.updateCopilotFromDto(
                request.content.parseToCopilotDto(),
                request.content,
                this,
                request.status,
            )
            uploadTime = LocalDateTime.now()
        }.apply {
            Cache.invalidateCopilotInfoByCid(copilotId)
            segmentService.updateIndex(copilotId!!, doc?.title, doc?.details)
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
        requireNotNull(copilotRepository.existsCopilotsByCopilotId(request.id)) { "作业id不存在" }

        val ratingChange = ratingService.rateCopilot(
            request.id,
            userIdOrIpAddress,
            RatingType.fromRatingType(request.rating),
        )
        val (likeCountChange, dislikeCountChange) = ratingService.calcLikeChange(ratingChange)

        // 获取只包含评分的作业
        var query = Query.query(
            Criteria.where("copilotId").`is`(request.id).and("delete").`is`(false),
        )
        // 排除 _id，防止误 save 该不完整作业后原有数据丢失
        query.fields().include("likeCount", "dislikeCount").exclude("_id")
        val copilot = mongoTemplate.findOne(query, Copilot::class.java)
        checkNotNull(copilot) { "作业不存在" }

        // 计算评分相关
        val likeCount = (copilot.likeCount + likeCountChange).coerceAtLeast(0)
        val ratingCount = (likeCount + copilot.dislikeCount + dislikeCountChange).coerceAtLeast(0)

        val rawRatingLevel = if (ratingCount != 0L) likeCount.toDouble() / ratingCount else 0.0
        // 只取一位小数点
        val ratingLevel = rawRatingLevel.toBigDecimal().setScale(1, RoundingMode.HALF_UP).toDouble()
        // 更新数据
        query = Query.query(
            Criteria.where("copilotId").`is`(request.id).and("delete").`is`(false),
        )
        val update = Update()
        update["likeCount"] = likeCount
        update["dislikeCount"] = ratingCount - likeCount
        update["ratingLevel"] = (ratingLevel * 10).toInt()
        update["ratingRatio"] = ratingLevel
        mongoTemplate.updateFirst(query, update, Copilot::class.java)

        // 记录近期评分变化量前 100 的作业 id
        redisCache.incZSet(
            "rate:hot:copilotIds",
            request.id.toString(),
            1.0,
            100,
            (3600 * 3).toLong(),
        )
    }


    private fun CopilotEntity.format(rating: Rating?, userName: String, commentsCount: Long) = CopilotInfo(
        id = copilotId,
        uploadTime = uploadTime,
        uploaderId = uploaderId,
        uploader = userName,
        views = views,
        hotScore = hotScore,
        available = true,
        ratingLevel = ratingLevel,
        notEnoughRating = likeCount + dislikeCount <= properties.copilot.minValueShowNotEnoughRating,
        ratingRatio = ratingRatio,
        ratingType = (rating?.rating ?: RatingType.NONE).display,
        commentsCount = commentsCount,
        commentStatus = commentStatus,
        content = content,
        like = likeCount,
        dislike = dislikeCount,
        status = status,
    )

    /**
     * 将数据库内容转换为前端所需格式
     */
    private fun Copilot.format(rating: Rating?, userName: String, commentsCount: Long) = CopilotInfo(
        id = copilotId!!,
        uploadTime = uploadTime!!,
        uploaderId = uploaderId!!,
        uploader = userName,
        views = views,
        hotScore = hotScore,
        available = true,
        ratingLevel = ratingLevel,
        notEnoughRating = likeCount + dislikeCount <= properties.copilot.minValueShowNotEnoughRating,
        ratingRatio = ratingRatio,
        ratingType = (rating?.rating ?: RatingType.NONE).display,
        commentsCount = commentsCount,
        commentStatus = commentStatus ?: CommentStatus.ENABLED,
        content = content ?: "",
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

    fun userEditCopilot(userId: String?, copilotId: Long?, edit: Copilot.() -> Unit): Copilot {
        val cId = copilotId.requireNotNull { "copilotId 不能为空" }
        val copilot = copilotRepository.findByCopilotIdAndDeleteIsFalse(cId).requireNotNull { "copilot 不存在" }
        require(copilot.uploaderId == userId) { "您没有权限修改" }
        return copilot.apply(edit).run(copilotRepository::save)
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
        fun getHotScore(copilot: Copilot, lastWeekLike: Long, lastWeekDislike: Long): Double {
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
