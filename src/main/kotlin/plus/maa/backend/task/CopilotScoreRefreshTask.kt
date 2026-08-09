package plus.maa.backend.task

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
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
    private val copilotRepo: CopilotRepository,
    private val ratingRepo: RatingRepository,
    private val arkLevelService: ArkLevelService,
    private val redisCache: RedisCache,
) {
    /**
     * 热度值刷入任务，每日四点三十执行（实际可能会更晚，因为需要等待之前启动的定时任务完成）
     */
    @Scheduled(cron = "0 30 4 * * ?", zone = "Asia/Shanghai")
    fun refreshHotScores() {
        // 分页获取所有未删除的作业
        var offset = 0
        val pageSize = 1000
        val count = copilotRepo.countNotDeleted()
        var copilots = copilotRepo.findNotDeletedPage(offset, pageSize)

        // 循环读取直到没有未删除的作业为止
        while (copilots.isNotEmpty()) {
            val copilotIds = copilots.map { copilot ->
                copilot.copilotId
            }.toList()
            refresh(copilotIds, copilots)
            // 获取下一页
            offset += pageSize
            if (offset.toLong() >= count) {
                // 没有下一页了，跳出循环
                break
            }
            copilots = copilotRepo.findNotDeletedPage(offset, pageSize)
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

        val copilots = copilotRepo.findByIdsAndNotDeleted(copilotIds)
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
        copilotRepo.batchUpdateHotScores(copilots.associate { it.copilotId to it.hotScore })
    }

    private fun counts(keys: Collection<String?>, rating: RatingType, startTime: LocalDateTime): List<RatingCount> {
        return ratingRepo.countByTypeKeyInRatingAfter(
            Rating.KeyType.COPILOT,
            keys.filterNotNull(),
            rating,
            startTime,
        )
    }
}
