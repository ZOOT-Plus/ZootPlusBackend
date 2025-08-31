package plus.maa.backend.service

import cn.hutool.core.lang.Assert
import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.dsl.inList
import org.ktorm.dsl.like
import org.ktorm.dsl.or
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.sortedBy
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.springframework.stereotype.Service
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.common.utils.IdComponent
import plus.maa.backend.common.utils.converter.CopilotSetConverter
import plus.maa.backend.controller.request.copilotset.CopilotSetCreateReq
import plus.maa.backend.controller.request.copilotset.CopilotSetModCopilotsReq
import plus.maa.backend.controller.request.copilotset.CopilotSetQuery
import plus.maa.backend.controller.request.copilotset.CopilotSetUpdateReq
import plus.maa.backend.controller.response.copilotset.CopilotSetListRes
import plus.maa.backend.controller.response.copilotset.CopilotSetRes
import plus.maa.backend.repository.entity.CopilotSet
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.repository.entity.copilotIdsList
import plus.maa.backend.repository.entity.copilotSets
import plus.maa.backend.repository.entity.distinctIdsAndCheck
import plus.maa.backend.repository.entity.setCopilotIdsList
import plus.maa.backend.repository.ktorm.CopilotSetKtormRepository
import plus.maa.backend.repository.ktorm.UserFollowingKtormRepository
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

/**
 * @author dragove
 * create on 2024-01-01
 */
@Service
class CopilotSetService(
    private val database: Database,
    private val idComponent: IdComponent,
    private val converter: CopilotSetConverter,
    private val userFollowingKtormRepository: UserFollowingKtormRepository,
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
    fun create(req: CopilotSetCreateReq, userId: String?): Long {
        val id = idComponent.getId(CopilotSet.meta)
        val now = LocalDateTime.now()

        val entity = CopilotSetEntity {
            this.id = id
            this.name = req.name
            this.description = req.description
            this.creatorId = userId!!
            this.createTime = now
            this.updateTime = now
            this.status = req.status
            this.delete = false
        }
        entity.setCopilotIdsList(req.copilotIds)

        copilotSetKtormRepository.insertEntity(entity)
        return id
    }

    /**
     * 往作业集中加入作业id列表
     */
    fun addCopilotIds(req: CopilotSetModCopilotsReq, userId: String) {
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        val currentIds = copilotSet.copilotIdsList
        currentIds.addAll(req.copilotIds)
        copilotSet.setCopilotIdsList(copilotSet.distinctIdsAndCheck())
        copilotSetKtormRepository.updateEntity(copilotSet)
    }

    /**
     * 往作业集中删除作业id列表
     */
    fun removeCopilotIds(req: CopilotSetModCopilotsReq, userId: String) {
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        val removeIds: Set<Long> = HashSet(req.copilotIds)
        val currentIds = copilotSet.copilotIdsList
        currentIds.removeIf { o: Long -> removeIds.contains(o) }
        copilotSet.setCopilotIdsList(currentIds)
        copilotSetKtormRepository.updateEntity(copilotSet)
    }

    /**
     * 更新作业集信息
     */
    fun update(req: CopilotSetUpdateReq, userId: String) {
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
            copilotSet.setCopilotIdsList(req.copilotIds)
            copilotSet.distinctIdsAndCheck()
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
    fun delete(id: Long, userId: String) {
        log.info { "delete copilot set for id: $id, userId: $userId" }
        val copilotSet = copilotSetKtormRepository.findByIdAsOptional(id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权删除该作业集")
        copilotSet.delete = true
        copilotSetKtormRepository.updateEntity(copilotSet)
    }

    fun query(req: CopilotSetQuery, userId: String?): PagedDTO<CopilotSetListRes> {
        val page = req.page - 1
        val limit = req.limit
        val offset = page * limit

        var copilotSetsSeq = database.copilotSets.filter { it.delete eq false }

        // 权限过滤
        copilotSetsSeq = if (userId.isNullOrBlank()) {
            copilotSetsSeq.filter { it.status eq CopilotSetStatus.PUBLIC }
        } else {
            copilotSetsSeq.filter {
                (it.status eq CopilotSetStatus.PUBLIC) or (it.creatorId eq userId)
            }
        }

        // 只关注的用户
        if (req.onlyFollowing && userId != null) {
            val followingIds = userFollowingKtormRepository.getFollowingIds(userId)
            if (followingIds.isEmpty()) {
                return PagedDTO(false, 0, 0, emptyList())
            }
            copilotSetsSeq = copilotSetsSeq.filter { it.creatorId inList followingIds }
        }

        // 创建者过滤
        if (!req.creatorId.isNullOrBlank()) {
            val targetCreatorId = if (req.creatorId == "me" && userId != null) userId else req.creatorId
            copilotSetsSeq = copilotSetsSeq.filter { it.creatorId eq targetCreatorId }
        }

        // 关键词搜索
        if (!req.keyword.isNullOrBlank()) {
            val keyword = "%${req.keyword}%"
            copilotSetsSeq = copilotSetsSeq.filter {
                (it.name like keyword) or (it.description like keyword)
            }
        }

        // 作业ID过滤（这个需要特殊处理，因为copilotIds是JSON字符串）
        var copilotSets = copilotSetsSeq.sortedBy { it.id }.drop(offset).take(limit).toList()

        // 如果有copilotIds过滤条件，需要在内存中过滤
        if (!req.copilotIds.isNullOrEmpty()) {
            copilotSets = copilotSets.filter { entity ->
                val entityCopilotIds = entity.copilotIdsList
                req.copilotIds.all { entityCopilotIds.contains(it) }
            }
        }

        val totalCount = copilotSetsSeq.count().toLong()
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
