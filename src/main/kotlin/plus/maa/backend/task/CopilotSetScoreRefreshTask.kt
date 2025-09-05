package plus.maa.backend.task

import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.repository.CopilotRepository
import plus.maa.backend.repository.CopilotSetRepository
import plus.maa.backend.service.CopilotSetService

/**
 * 作业集热度值刷入任务，每日执行，用于计算基于收录作业热度的作业集热度值
 *
 * @author Pleasurecruise
 * created on 2025-09-05
 */
@Component
class CopilotSetScoreRefreshTask(
    private val copilotSetRepository: CopilotSetRepository,
    private val copilotRepository: CopilotRepository,
) {
    /**
     * 作业集热度值刷入任务，每日五点执行
     */
    @Scheduled(cron = "0 0 5 * * ?", zone = "Asia/Shanghai")
    fun refreshCopilotSetHotScores() {
        // 分页获取所有未删除的作业集
        var pageable = Pageable.ofSize(1000)
        var copilotSets = copilotSetRepository.findAllByDeleteIsFalse(pageable)

        // 循环读取直到没有未删除的作业集为止
        while (copilotSets.hasContent()) {
            val copilotSetList = copilotSets.content
            for (copilotSet in copilotSetList) {
                // 获取作业集中的所有作业
                val copilots = copilotRepository.findByCopilotIdInAndDeleteIsFalse(copilotSet.copilotIds)

                // 计算并更新热度分数
                copilotSet.hotScore = CopilotSetService.getHotScore(copilotSet, copilots)
            }

            // 批量保存更新后的作业集
            copilotSetRepository.saveAll(copilotSetList)

            // 获取下一页
            if (!copilotSets.hasNext()) {
                // 没有下一页了，跳出循环
                break
            }
            pageable = copilotSets.nextPageable()
            copilotSets = copilotSetRepository.findAllByDeleteIsFalse(pageable)
        }
    }
}
