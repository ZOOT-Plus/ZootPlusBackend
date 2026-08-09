package plus.maa.backend.service

import cn.hutool.core.lang.Assert
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import plus.maa.backend.common.Constants.COPILOT_SET_VIEW_KEY
import plus.maa.backend.common.Constants.ME
import plus.maa.backend.common.Constants.VISITED_FLAG
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.common.utils.converter.CopilotSetConverter
import plus.maa.backend.controller.request.copilotset.CopilotSetCreateReq
import plus.maa.backend.controller.request.copilotset.CopilotSetModCopilotsReq
import plus.maa.backend.controller.request.copilotset.CopilotSetQuery
import plus.maa.backend.controller.request.copilotset.CopilotSetUpdateReq
import plus.maa.backend.controller.response.copilotset.CopilotSetListRes
import plus.maa.backend.controller.response.copilotset.CopilotSetRes
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.repository.entity.setCopilotIdsWithCheck
import plus.maa.backend.repository.ktorm.CopilotSetRepository
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.hours

/**
 * @author dragove
 * create on 2024-01-01
 */
@Service
class CopilotSetService(
    private val copilotSetRepository: CopilotSetRepository,
    private val converter: CopilotSetConverter,
    private val redisCache: RedisCache,
    private val userService: UserService,
) {
    private val log = KotlinLogging.logger { }

    /**
     * 创建作业集
     *
     * @param req    作业集创建请求
     * @param userId 创建者用户id
     * @return 作业集id
     */
    fun create(req: CopilotSetCreateReq, userId: Long): Long {
        val now = LocalDateTime.now()

        val entity = CopilotSetEntity(
            name = req.name,
            description = req.description,
            copilotIds = emptyList(),
            views = 0L,
            hotScore = 0.0,
            creatorId = userId,
            createTime = now,
            updateTime = now,
            status = req.status,
            delete = false,
        )
        entity.setCopilotIdsWithCheck(req.copilotIds)

        copilotSetRepository.insertEntity(entity)
        return entity.id
    }

    /**
     * 往作业集中加入作业id列表
     */
    fun addCopilotIds(req: CopilotSetModCopilotsReq, userId: Long) {
        val copilotSet = copilotSetRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        val currentIds = LinkedHashSet(copilotSet.copilotIds)
        currentIds.addAll(req.copilotIds)
        copilotSet.setCopilotIdsWithCheck(currentIds)
        copilotSetRepository.updateEntity(copilotSet)
    }

    /**
     * 往作业集中删除作业id列表
     */
    fun removeCopilotIds(req: CopilotSetModCopilotsReq, userId: Long) {
        val copilotSet = copilotSetRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        val removeIds: Set<Long> = HashSet(req.copilotIds)
        val currentIds = LinkedHashSet(copilotSet.copilotIds)
        currentIds.removeAll(removeIds)
        copilotSet.setCopilotIdsWithCheck(currentIds)
        copilotSetRepository.updateEntity(copilotSet)
    }

    /**
     * 更新作业集信息
     */
    fun update(req: CopilotSetUpdateReq, userId: Long) {
        val copilotSet = copilotSetRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        if (!req.name.isNullOrBlank()) {
            copilotSet.name = req.name
        }
        if (req.description != null) {
            copilotSet.description = req.description
        }
        if (req.status != null) {
            copilotSet.status = req.status
        }
        if (req.copilotIds != null) {
            copilotSet.setCopilotIdsWithCheck(req.copilotIds)
        }
        copilotSet.updateTime = LocalDateTime.now()
        copilotSetRepository.updateEntity(copilotSet)
    }

    /**
     * 删除作业集信息（逻辑删除，保留详情接口查询结果）
     *
     * @param id     作业集id
     * @param userId 登陆用户id
     */
    fun delete(id: Long, userId: Long) {
        log.info { "delete copilot set for id: $id, userId: $userId" }
        val copilotSet = copilotSetRepository.findByIdAsOptional(id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权删除该作业集")
        copilotSet.delete = true
        copilotSetRepository.updateEntity(copilotSet)
    }

    fun query(req: CopilotSetQuery, userId: Long?): PagedDTO<CopilotSetListRes> {
        val page = req.page - 1
        val limit = req.limit
        val offset = page * limit

        // 创建者过滤（ME 特判：登录时等价于自己的 id；非法 id 解析失败 → 空结果）
        val targetCreatorId: Long? = if (!req.creatorId.isNullOrBlank()) {
            if (req.creatorId == ME && userId != null) {
                userId
            } else {
                req.creatorId.toLongOrNull() ?: return PagedDTO(false, 0, 0, emptyList())
            }
        } else {
            null
        }

        val keyword = req.keyword?.takeIf { it.isNotBlank() }?.let { "%$it%" }

        val requiredIds = req.copilotIds?.takeIf { it.isNotEmpty() }?.toSet()

        val (copilotSets, totalCount) = copilotSetRepository.querySets(
            userId = userId,
            onlyFollowing = req.onlyFollowing,
            creatorId = targetCreatorId,
            keyword = keyword,
            copilotIds = requiredIds,
            offset = offset,
            limit = limit,
        )

        val hasNext = (offset + limit) < totalCount
        val totalPages = ((totalCount + limit - 1) / limit).toInt()

        val results = copilotSets.map { cs ->
            val user = userService.findByUserIdOrDefaultInCache(cs.creatorId)
            converter.convert(cs, user.userName)
        }

        return PagedDTO(hasNext, totalPages, totalCount, results)
    }

    fun get(id: Long, userIdOrIpAddress: String): CopilotSetRes {
        val copilotSet = copilotSetRepository.findByIdAsOptional(id).orElseThrow {
            IllegalArgumentException("作业集不存在")
        }
        // 60分钟内限制同一个用户对访问量的增加
        val key = COPILOT_SET_VIEW_KEY(id, userIdOrIpAddress)
        val visitResult = redisCache.setCacheIfAbsent(
            key,
            VISITED_FLAG,
            1.hours,
        )

        if (visitResult) {
            Thread.startVirtualThread {
                copilotSetRepository.incrViews(id)
            }
        }
        val userName = userService.findByUserIdOrDefaultInCache(copilotSet.creatorId).userName
        return converter.convertDetail(copilotSet, userName)
    }
}
