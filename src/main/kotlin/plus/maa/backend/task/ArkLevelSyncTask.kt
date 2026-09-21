package plus.maa.backend.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.level.LevelNameRepairSource
import java.util.concurrent.atomic.AtomicBoolean

@Component
class ArkLevelSyncTask(
    private val arkLevelService: ArkLevelService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val levelSyncing = AtomicBoolean(false)
    private val openStatusSyncing = AtomicBoolean(false)
    private val nameRepairing = AtomicBoolean(false)

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
        try {
            awaitAll(
                async { arkLevelService.updateActivitiesOpenStatus() },
                async { arkLevelService.updateCrisisV2OpenStatus() },
            )
        } finally {
            // 每日兜底回填缺失的活动名：活动名可能晚于地图文件发布，同步那一刻拿不到名字的行
            // 需要后续重试才能补上。放在 finally 里，开放状态更新失败也不影响回填执行。
            // 挂在硬编码的既有任务下，保证不依赖默认禁用的 maa-copilot.task-cron.* 配置项。
            arkLevelService.repairMissingActivityNames(LevelNameRepairSource.DAILY)
        }
    }

    /**
     * 应用启动后就绪回填一次缺失的活动名，无需任何人工操作即可完成历史数据修复。
     *
     * 用 [ApplicationReadyEvent] 而非 @PostConstruct：此时数据库与 Flyway 均已就绪；
     * 修复过程会访问数据库与网络，放在后台协程里执行，不阻塞启动。
     */
    @EventListener(ApplicationReadyEvent::class)
    fun repairMissingActivityNamesOnStartup() = atomRun(nameRepairing) {
        arkLevelService.repairMissingActivityNames(LevelNameRepairSource.STARTUP)
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
