package plus.maa.backend.task

import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.batchUpdate
import org.ktorm.dsl.eq
import org.ktorm.dsl.inList
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.repository.entity.CopilotSets
import plus.maa.backend.repository.entity.copilotSets
import plus.maa.backend.repository.entity.copilots
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

@Component
class CopilotSetScoreRefreshTask(
    private val database: Database,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 每天凌晨5点刷新作业集热度分数
     */
    @Scheduled(cron = "0 0 5 * * ?", zone = "Asia/Shanghai")
    fun refresh() {
        try {
            // 统计未删除的作业集总数
            val total = database.copilotSets.filter { it.delete eq false }.count()
            if (total == 0) {
                log.info { "没有需要更新的作业集" }
                return
            }

            val pageSize = 1000
            var offset = 0

            while (offset < total) {
                try {
                    val copilotSets = database.copilotSets
                        .filter { it.delete eq false }
                        .drop(offset)
                        .take(pageSize)
                        .toList()

                    if (copilotSets.isEmpty()) break

                    val scoreMap = mutableMapOf<Long, Double>()
                    copilotSets.forEach { s ->
                        try {
                            val copilots = if (s.copilotIds.isEmpty()) {
                                emptyList()
                            } else {
                                database.copilots
                                    .filter { (it.copilotId inList s.copilotIds) and (it.delete eq false) }
                                    .toList()
                            }

                            scoreMap[s.id] = score(s, copilots)
                        } catch (e: Exception) {
                            log.error(e) { "计算作业集 ${s.id} 热度分数失败" }
                        }
                    }

                    // 批量更新热度分数
                    if (scoreMap.isNotEmpty()) {
                        database.batchUpdate(CopilotSets) {
                            scoreMap.forEach { (id, newScore) ->
                                item {
                                    set(it.hotScore, newScore)
                                    where {
                                        it.id eq id
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error(e) { "处理第 ${offset / pageSize + 1} 页时出错" }
                } finally {
                    offset += pageSize
                }
            }
        } catch (e: Exception) {
            log.error(e) { "刷新作业集热度分数失败" }
        }
    }

    private fun score(copilotSet: CopilotSetEntity, copilots: List<CopilotEntity>): Double {
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
