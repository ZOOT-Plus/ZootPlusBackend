package plus.maa.backend.service

import cn.hutool.core.lang.Assert
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.support.PageableExecutionUtils
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
import plus.maa.backend.repository.CopilotSetRepository
import plus.maa.backend.repository.UserFollowingRepository
import plus.maa.backend.repository.entity.CopilotSet
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern
import kotlin.math.*
import plus.maa.backend.common.extensions.blankAsNull
import plus.maa.backend.repository.entity.Copilot
import plus.maa.backend.repository.CopilotRepository

/**
 * @author dragove
 * create on 2024-01-01
 */
@Service
class CopilotSetService(
    private val idComponent: IdComponent,
    private val converter: CopilotSetConverter,
    private val userFollowingRepository: UserFollowingRepository,
    private val repository: CopilotSetRepository,
    private val copilotRepository: CopilotRepository,
    private val userService: UserService,
    private val mongoTemplate: MongoTemplate,
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
        val newCopilotSet = converter.convert(req, id, userId!!)
        repository.insert(newCopilotSet)
        return id
    }

    /**
     * 往作业集中加入作业id列表
     */
    fun addCopilotIds(req: CopilotSetModCopilotsReq, userId: String) {
        val copilotSet = repository.findById(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        copilotSet.copilotIds.addAll(req.copilotIds)
        copilotSet.copilotIds = copilotSet.distinctIdsAndCheck()
        repository.save(copilotSet)
    }

    /**
     * 往作业集中删除作业id列表
     */
    fun removeCopilotIds(req: CopilotSetModCopilotsReq, userId: String) {
        val copilotSet = repository.findById(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权修改该作业集")
        val removeIds: Set<Long> = HashSet(req.copilotIds)
        copilotSet.copilotIds.removeIf { o: Long -> removeIds.contains(o) }
        repository.save(copilotSet)
    }

    /**
     * 更新作业集信息
     */
    fun update(req: CopilotSetUpdateReq, userId: String) {
        val copilotSet = repository.findById(req.id).orElseThrow { IllegalArgumentException("作业集不存在") }
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
            copilotSet.copilotIds = req.copilotIds
            copilotSet.distinctIdsAndCheck()
        }
        repository.save(copilotSet)
    }

    /**
     * 删除作业集信息（逻辑删除，保留详情接口查询结果）
     *
     * @param id     作业集id
     * @param userId 登陆用户id
     */
    fun delete(id: Long, userId: String) {
        log.info { "delete copilot set for id: $id, userId: $userId" }
        val copilotSet = repository.findById(id).orElseThrow { IllegalArgumentException("作业集不存在") }
        Assert.state(copilotSet.creatorId == userId, "您不是该作业集的创建者，无权删除该作业集")
        copilotSet.delete = true
        copilotSet.deleteTime = LocalDateTime.now()
        repository.save(copilotSet)
    }

    fun query(req: CopilotSetQuery, userId: String?): PagedDTO<CopilotSetListRes> {
        val sortOrder = Sort.Order(
            if (req.desc) Sort.Direction.DESC else Sort.Direction.ASC,
            req.orderBy?.blankAsNull().let { ob ->
                when (ob) {
                    "hot" -> "hotScore"
                    "id" -> "id"
                    "views" -> "views"
                    "createTime" -> "createTime"
                    "updateTime" -> "updateTime"
                    else -> "id"
                }
            } ?: "id",
        )
        val pageRequest = PageRequest.of(req.page - 1, req.limit, Sort.by(sortOrder))

        val andList = ArrayList<Criteria>()
        val publicCriteria = Criteria.where("status").`is`(CopilotSetStatus.PUBLIC)
        val permissionCriterion = if (userId.isNullOrBlank()) {
            publicCriteria
        } else {
            Criteria().orOperator(publicCriteria, Criteria.where("creatorId").`is`(userId))
        }
        andList.add(permissionCriterion)
        andList.add(Criteria.where("delete").`is`(false))

        if (req.onlyFollowing && userId != null) {
            val userFollowing = userFollowingRepository.findByUserId(userId)
            val followingIds = userFollowing?.followList ?: emptyList()
            if (followingIds.isEmpty()) {
                return PagedDTO(false, 0, 0, emptyList())
            }

            andList.add(Criteria.where("creatorId").`in`(followingIds))
        }

        if (!req.copilotIds.isNullOrEmpty()) {
            andList.add(Criteria.where("copilotIds").all(req.copilotIds))
        }
        if (!req.creatorId.isNullOrBlank()) {
            if (req.creatorId == "me" && userId != null) {
                andList.add(Criteria.where("creatorId").`is`(userId))
            } else {
                andList.add(Criteria.where("creatorId").`is`(req.creatorId))
            }
        }
        if (!req.keyword.isNullOrBlank()) {
            val pattern = Pattern.compile(req.keyword, Pattern.CASE_INSENSITIVE)
            andList.add(
                Criteria().orOperator(
                    Criteria.where("name").regex(pattern),
                    Criteria.where("description").regex(pattern),
                ),
            )
        }
        val query = Query.query(Criteria().andOperator(andList)).with(pageRequest)
        val copilotSets = PageableExecutionUtils.getPage(mongoTemplate.find(query, CopilotSet::class.java), pageRequest) {
            mongoTemplate.count(
                query.limit(-1).skip(-1),
                CopilotSet::class.java,
            )
        }
        val userIds = copilotSets.map { obj: CopilotSet -> obj.creatorId }.distinct().toList()
        val userById = userService.findByUsersId(userIds)
        return PagedDTO(
            copilotSets.hasNext(),
            copilotSets.totalPages,
            copilotSets.totalElements,
            copilotSets.map { cs: CopilotSet ->
                val user = userById.getOrDefault(cs.creatorId)
                converter.convert(cs, user.userName)
            }.toList(),
        )
    }

    fun get(id: Long): CopilotSetRes = repository.findById(id).map { copilotSet: CopilotSet ->
        // 增加浏览次数
        copilotSet.views++
        repository.save(copilotSet)
        
        val userName = userService.findByUserIdOrDefaultInCache(copilotSet.creatorId).userName
        converter.convertDetail(copilotSet, userName)
    }.orElseThrow { IllegalArgumentException("作业不存在") }

    companion object {
        /**
         * 计算作业集热度分数
         * 基于收录作业的热度、创建时间、浏览量等因素综合计算
         */
        fun getHotScore(copilotSet: CopilotSet, copilots: List<Copilot>): Double {
            val now = LocalDateTime.now()
            val createTime = copilotSet.createTime
            
            // 基础分
            var base = 5.0
            
            // 时间衰减（相比创建时间过了多少周）
            val pastedWeeks = ChronoUnit.WEEKS.between(createTime, now) + 1
            base /= ln((pastedWeeks + 1).toDouble())
            
            // 收录作业的平均热度分数
            val avgCopilotScore = if (copilots.isNotEmpty()) {
                copilots.map { it.hotScore }.average()
            } else {
                0.0
            }
            
            // 作业数量加成（但有上限，避免无意义堆积）
            val copilotCountBonus = min(copilots.size.toDouble() / 10.0, 2.0)
            
            // 浏览量因子
            val viewsFactor = copilotSet.views / 100.0
            
            // 综合计算
            val score = (avgCopilotScore * copilotCountBonus * max(viewsFactor, 1.0)) / pastedWeeks
            val order = ln(max(score, 1.0))
            
            return order + score / 1000.0 + base
        }
    }
}
