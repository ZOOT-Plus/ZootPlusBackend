package plus.maa.backend.service

import cn.hutool.core.lang.Assert
import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.dsl.like
import org.ktorm.dsl.or
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.sortedBy
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.springframework.stereotype.Service
import plus.maa.backend.common.Constants.ME
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.common.utils.converter.CopilotSetConverter
import plus.maa.backend.controller.request.copilotset.CopilotSetCreateReq
import plus.maa.backend.controller.request.copilotset.CopilotSetModCopilotsReq
import plus.maa.backend.controller.request.copilotset.CopilotSetQuery
import plus.maa.backend.controller.request.copilotset.CopilotSetUpdateReq
import plus.maa.backend.controller.response.copilotset.CopilotSetListRes
import plus.maa.backend.controller.response.copilotset.CopilotSetRes
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.repository.entity.copilotSets
import plus.maa.backend.repository.entity.setCopilotIdsWithCheck
import plus.maa.backend.repository.ktorm.CopilotSetKtormRepository
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

/**
 * @author dragove
 * create on 2024-01-01
 */
@Service
class CopilotSetService(
    private val database: Database,
    private val converter: CopilotSetConverter,
    private val copilotSetKtormRepository: CopilotSetKtormRepository,
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

        val entity = CopilotSetEntity {
            this.name = req.name
            this.description = req.description
            this.creatorId = userId
            this.createTime = now
            this.updateTime = now
            this.status = req.status
            this.delete = false
        }
        entity.setCopilotIdsWithCheck(req.copilotIds)

        copilotSetKtormRepository.insertEntity(entity)
        return entity.id
    }

    /**
     * 往作业集中加入作业id列表
     */
    fun addCopilotIds(req: CopilotSetModCopilotsReq, userId: Long) {
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        val currentIds = LinkedHashSet(copilotSet.copilotIds)
        currentIds.addAll(req.copilotIds)
        copilotSet.setCopilotIdsWithCheck(currentIds)
        copilotSetKtormRepository.updateEntity(copilotSet)
    }

    /**
     * 往作业集中删除作业id列表
     */
    fun removeCopilotIds(req: CopilotSetModCopilotsReq, userId: Long) {
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        val removeIds: Set<Long> = HashSet(req.copilotIds)
        val currentIds = LinkedHashSet(copilotSet.copilotIds)
        currentIds.removeAll(removeIds)
        copilotSet.setCopilotIdsWithCheck(currentIds)
        copilotSetKtormRepository.updateEntity(copilotSet)
    }

    /**
     * 更新作业集信息
     */
    fun update(req: CopilotSetUpdateReq, userId: Long) {
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
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
        copilotSetKtormRepository.updateEntity(copilotSet)
    }

    /**
     * 删除作业集信息（逻辑删除，保留详情接口查询结果）
     *
     * @param id     作业集id
     * @param userId 登陆用户id
     */
    fun delete(id: Long, userId: Long) {
        log.info { "delete copilot set for id: $id, userId: $userId" }
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权删除该作业集")
        copilotSet.delete = true
        copilotSetKtormRepository.updateEntity(copilotSet)
    }

    fun query(req: CopilotSetQuery, userId: Long?): PagedDTO<CopilotSetListRes> {
        val page = req.page - 1
        val limit = req.limit
        val offset = page * limit

        var sequence = database.copilotSets.filter { it.delete eq false }

        // 权限过滤
        sequence = if (userId == null) {
            sequence.filter { it.status eq CopilotSetStatus.PUBLIC }
        } else {
            sequence.filter {
                (it.status eq CopilotSetStatus.PUBLIC) or (it.creatorId eq userId)
            }
        }

        // 只关注的用户
        if (req.onlyFollowing && userId != null) {
            // TODO FOLLOW功能 issue195
        }

        // 创建者过滤
        if (!req.creatorId.isNullOrBlank()) {
            val targetCreatorId: Long = if (req.creatorId == ME && userId != null) {
                userId
            } else {
                req.creatorId.toLongOrNull() ?: return PagedDTO(false, 0, 0, emptyList())
            }
            sequence = sequence.filter { it.creatorId eq targetCreatorId }
        }

        // 关键词搜索
        if (!req.keyword.isNullOrBlank()) {
            val keyword = "%${req.keyword}%"
            sequence = sequence.filter {
                (it.name like keyword) or (it.description like keyword)
            }
        }

        // 作业ID过滤
        var copilotSets = sequence.sortedBy { it.id }.drop(offset).take(limit).toList()

        // 如果有copilotIds过滤条件，需要在内存中过滤
        if (!req.copilotIds.isNullOrEmpty()) {
            val requiredIds = req.copilotIds.toSet()
            copilotSets = copilotSets.filter { entity ->
                val entityIds = entity.copilotIds.toSet()
                entityIds.containsAll(requiredIds)
            }
        }

        val totalCount = sequence.count().toLong()
        val hasNext = (offset + limit) < totalCount
        val totalPages = ((totalCount + limit - 1) / limit).toInt()

        val userIds = copilotSets.map { entity -> entity.creatorId }.distinct()
        val userById = userService.findByUsersId(userIds)

        val results = copilotSets.map { cs ->
            val user = userById.getOrDefault(cs.creatorId)
            converter.convert(cs, user.userName)
        }

        return PagedDTO(hasNext, totalPages, totalCount, results)
    }

    fun get(id: Long): CopilotSetRes {
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(id).orElseThrow { IllegalArgumentException("作业不存在") }
        val userName = userService.findByUserIdOrDefaultInCache(copilotSet.creatorId).userName
        return converter.convertDetail(copilotSet, userName)
    }
}
