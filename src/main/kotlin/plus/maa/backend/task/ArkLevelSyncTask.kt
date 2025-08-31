package plus.maa.backend.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.service.level.ArkLevelService
import java.util.concurrent.atomic.AtomicBoolean

@Component
class ArkLevelSyncTask(
    private val arkLevelService: ArkLevelService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val levelSyncing = AtomicBoolean(false)
    private val openStatusSyncing = AtomicBoolean(false)

    /**
     * 地图数据同步定时任务，每10分钟执行一次
     * 应用启动时自动同步一次
     */
    @Scheduled(cron = $$"${maa-copilot.task-cron.ark-level:-}", zone = "Asia/Shanghai")
    fun syncArkLevels() = atomRun(levelSyncing) {
        arkLevelService.syncLevelData()
    }

    /**
     * 更新开放状态，每天凌晨执行，最好和热度值刷入任务保持相对顺序
     * 4:00、4:15 各执行一次，避免网络波动导致更新失败
     */
    @Scheduled(cron = "0 0-15/15 4 * * ?", zone = "Asia/Shanghai")
    fun updateOpenStatus() = atomRun(openStatusSyncing) {
        awaitAll(
            async { arkLevelService.updateActivitiesOpenStatus() },
            async { arkLevelService.updateCrisisV2OpenStatus() },
        )
    }

    private fun atomRun(atom: AtomicBoolean, block: suspend CoroutineScope.() -> Unit): Boolean {
        val permitted = atom.compareAndSet(false, true)
        if (permitted) {
            scope.launch {
                try {
                    block()
                } finally {
                    atom.set(false)
                }
            }
        }
        return permitted
    }
}
