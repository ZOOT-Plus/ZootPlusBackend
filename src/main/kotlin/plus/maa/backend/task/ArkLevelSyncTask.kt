package plus.maa.backend.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.level.LevelNameRepairSource
import plus.maa.backend.service.level.UpdatedAtBackfillSource
import java.util.concurrent.atomic.AtomicBoolean

@Component
class ArkLevelSyncTask(
    private val arkLevelService: ArkLevelService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val levelSyncing = AtomicBoolean(false)
    private val openStatusSyncing = AtomicBoolean(false)
    private val nameRepairing = AtomicBoolean(false)
    private val updatedAtBackfilling = AtomicBoolean(false)

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
            // 必须包一层 supervisorScope：awaitAll 中任一 async 失败会取消父 Job，若不隔离，
            // finally 里的兜底回填进入时协程已是取消态，第一个挂起点就抛 CancellationException
            // ——「开放状态更新失败也要回填」这层防护会静默失效（实测：进入函数但挂起点不执行）。
            supervisorScope {
                awaitAll(
                    async { arkLevelService.updateActivitiesOpenStatus() },
                    async { arkLevelService.updateCrisisV2OpenStatus() },
                )
            }
        } finally {
            // 每日兜底回填缺失的活动名：活动名可能晚于地图文件发布，同步那一刻拿不到名字的行
            // 需要后续重试才能补上。放在 finally 里，开放状态更新失败也不影响回填执行。
            // 挂在硬编码的既有任务下，保证不依赖默认禁用的 maa-copilot.task-cron.* 配置项。
            arkLevelService.repairMissingActivityNames(LevelNameRepairSource.DAILY)
            // 存量 updated_at 的兜底回填（正常情况下启动时已完成，此后每次只花一条 COUNT 查询）：
            // 覆盖「启动那次因网络/API 失败而没做完」的情况，否则那些行永远不会进入 lite 窗口。
            arkLevelService.backfillUpdatedAt(UpdatedAtBackfillSource.DAILY)
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

    /**
     * 应用启动后就绪回填一次存量行的 `updated_at`（`/arknights/level/v2` 的 lite 窗口依赖它）。
     *
     * 与活动名回填同样是幂等的：完成后每次启动只花一条 COUNT 查询，无待回填行则不触网。
     */
    @EventListener(ApplicationReadyEvent::class)
    fun backfillUpdatedAtOnStartup() = atomRun(updatedAtBackfilling) {
        arkLevelService.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)
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
