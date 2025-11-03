package plus.maa.backend.task

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.batchUpdate
import org.ktorm.dsl.count
import org.ktorm.dsl.eq
import org.ktorm.dsl.from
import org.ktorm.dsl.groupBy
import org.ktorm.dsl.gte
import org.ktorm.dsl.inList
import org.ktorm.dsl.map
import org.ktorm.dsl.select
import org.ktorm.dsl.where
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Copilots
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.Ratings
import plus.maa.backend.repository.entity.copilots
import plus.maa.backend.repository.ktorm.CopilotKtormRepository
import plus.maa.backend.service.CopilotService.Companion.getHotScore
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.model.RatingCount
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

/**
 * 作业热度值刷入任务，每日执行，用于计算基于时间的热度值
 *
 * @author dove
 * created on 2023.05.03
 */
@Component
class CopilotScoreRefreshTask(
    private val copilotRepo: CopilotKtormRepository,
    private val database: Database,
    private val arkLevelService: ArkLevelService,
    private val redisCache: RedisCache,
) {
    /**
     * 热度值刷入任务，每日四点三十执行（实际可能会更晚，因为需要等待之前启动的定时任务完成）
     */
    @Scheduled(cron = "0 30 4 * * ?", zone = "Asia/Shanghai")
    fun refreshHotScores() {
        // 分页获取所有未删除的作业
//        var pageable = Pageable.ofSize(1000)
//        var copilots = copilotRepository.findAllByDeleteIsFalse(pageable)
        var offset = 0
        val pageSize = 1000
        val query = copilotRepo.getNotDeletedQuery()
        val count = query.count()
        var copilots = query.take(pageSize).drop(offset).toList()

        // 循环读取直到没有未删除的作业为止
        while (copilots.isNotEmpty()) {
            val copilotIds = copilots.map { copilot ->
                copilot.copilotId
            }.toList()
            refresh(copilotIds, copilots)
            // 获取下一页
            offset += pageSize
            if (offset >= count) {
                // 没有下一页了，跳出循环
                break
            }
            offset += pageSize
            copilots = query.take(pageSize).drop(offset).toList()
        }

        // 移除首页热度缓存
        redisCache.syncRemoveCacheByPattern("home:hot:*")
    }

    /**
     * 刷入评分变更数 Top 100 的热度值，每日八点到二十点每三小时执行一次
     */
    @Scheduled(cron = "0 0 8-20/3 * * ?", zone = "Asia/Shanghai")
    fun refreshTop100HotScores() {
        val copilotIds = redisCache.getZSetReverse("rate:hot:copilotIds", 0, 99)?.map {
            it.toLong()
        }
        if (copilotIds.isNullOrEmpty()) {
            return
        }

        val copilots = database.copilots.filter {
            (it.copilotId inList copilotIds) and (it.delete eq false)
        }.toList()
        if (copilots.isEmpty()) {
            return
        }

        refresh(copilotIds, copilots)

        // 移除近期评分变化量缓存
        redisCache.removeCache("rate:hot:copilotIds")
        // 移除首页热度缓存
        redisCache.syncRemoveCacheByPattern("home:hot:*")
    }

    private fun refresh(copilotIds: Collection<Long>, copilots: Iterable<CopilotEntity>) {
        // 批量获取最近七天的点赞和点踩数量
        val now = LocalDateTime.now()
        val ids = copilotIds.map { it.toString() }
        val likeCounts = counts(ids, RatingType.LIKE, now.minusDays(7))
        val dislikeCounts = counts(ids, RatingType.DISLIKE, now.minusDays(7))
        val likeCountMap = likeCounts.associate { it.key to it.count }
        val dislikeCountMap = dislikeCounts.associate { it.key to it.count }
        // 计算热度值
        for (copilot in copilots) {
            val likeCount = likeCountMap.getOrDefault(copilot.copilotId.toString(), 1L)
            val dislikeCount = dislikeCountMap.getOrDefault(copilot.copilotId.toString(), 0L)
            var hotScore = getHotScore(copilot, likeCount, dislikeCount)
            // 判断关卡是否开放
            val level = arkLevelService.findByLevelIdFuzzy(copilot.stageName)
            // 关卡已关闭，且作业在关闭前上传
            if (level?.closeTime != null &&
                false == level.isOpen &&
                copilot.firstUploadTime.isBefore(level.closeTime)
            ) {
                // 非开放关卡打入冷宫

                hotScore /= 100.0
            }
            copilot.hotScore = hotScore
        }
        // 批量更新热度值
        database.batchUpdate(Copilots) {
            for (copilot in copilots) {
                item {
                    set(it.hotScore, copilot.hotScore)
                    where { it.copilotId eq copilot.copilotId }
                }
            }
        }
//        copilotRepository.saveAll(copilots)
    }

    private fun counts(keys: Collection<String?>, rating: RatingType, startTime: LocalDateTime): List<RatingCount> {
        // 使用Ktorm DSL进行GROUP BY查询，等价于MongoDB的聚合操作
        return database.from(Ratings)
            .select(Ratings.key, count(Ratings.id))
            .where {
                (Ratings.type eq Rating.KeyType.COPILOT) and
                    (Ratings.key inList keys.filterNotNull()) and
                    (Ratings.rating eq rating) and
                    (Ratings.rateTime gte startTime)
            }
            .groupBy(Ratings.key)
            .map { row ->
                RatingCount(
                    key = row[Ratings.key]!!,
                    count = row.getLong(2),
                )
            }
    }
}
