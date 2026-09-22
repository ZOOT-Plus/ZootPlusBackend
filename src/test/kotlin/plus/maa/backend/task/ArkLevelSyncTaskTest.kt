package plus.maa.backend.task

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.level.LevelNameRepairSource
import plus.maa.backend.service.level.LevelNameRepairStat

/**
 * [ArkLevelSyncTask.updateOpenStatus] 的防回归测试。
 *
 * 回归点：`awaitAll` 中任一 `async` 失败会取消父 Job，此时 `finally` 里的兜底回填已经是取消态，
 * 它在**第一个挂起点**（真实实现里的 `withContext(Dispatchers.IO)`）就会抛 CancellationException。
 * 即「开放状态更新失败也要执行回填」这层防护会静默失效——实测：进入函数但挂起点不执行。
 * 修复方式是把 `awaitAll` 包进 `supervisorScope`。
 *
 * 因此桩里必须放一个**真实挂起点**（而不是直接返回），否则取消态观察不到，测试会假绿。
 */
class ArkLevelSyncTaskTest {

    @Test
    fun `updateOpenStatus still runs daily name repair past its first suspension point`() {
        var suspendedPointReached = false
        var completed = false
        val service = mockk<ArkLevelService>(relaxed = true)
        coEvery { service.updateCrisisV2OpenStatus() } throws IllegalStateException("snapshot unavailable")
        coEvery { service.repairMissingActivityNames(LevelNameRepairSource.DAILY) } coAnswers {
            // 等价于真实实现的第一个挂起点（withContext(Dispatchers.IO) 内的 COUNT 查询）：
            // 若外层 Job 已被取消，这里会抛 CancellationException，后续语句不会执行
            withContext(Dispatchers.IO) { suspendedPointReached = true }
            completed = true
            LevelNameRepairStat(0, 0, 0, 0)
        }

        ArkLevelSyncTask(service).updateOpenStatus()

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !completed) Thread.sleep(20)

        assertTrue(suspendedPointReached, "回填应越过第一个挂起点（不能被取消）")
        assertTrue(completed, "开放状态更新失败时，每日回填仍须完整执行")
    }

    @Test
    fun `updateOpenStatus runs both open status updates and the repair when all succeed`() {
        var activitiesRan = false
        var crisisRan = false
        var repaired = false
        val service = mockk<ArkLevelService>(relaxed = true)
        coEvery { service.updateActivitiesOpenStatus() } coAnswers { activitiesRan = true }
        coEvery { service.updateCrisisV2OpenStatus() } coAnswers { crisisRan = true }
        coEvery { service.repairMissingActivityNames(LevelNameRepairSource.DAILY) } coAnswers {
            repaired = true
            LevelNameRepairStat(0, 0, 0, 0)
        }

        ArkLevelSyncTask(service).updateOpenStatus()

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !repaired) Thread.sleep(20)

        assertTrue(activitiesRan, "活动开放状态更新应执行")
        assertTrue(crisisRan, "危机合约开放状态更新应执行")
        assertTrue(repaired, "每日回填应执行")
    }

    @Test
    fun `updateOpenStatus is skipped while a previous run is still in flight`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var repairCount = 0
        val service = mockk<ArkLevelService>(relaxed = true)
        coEvery { service.updateActivitiesOpenStatus() } coAnswers {
            entered.countDown()
            withContext(Dispatchers.IO) { release.await() }
        }
        coEvery { service.repairMissingActivityNames(any<LevelNameRepairSource>()) } coAnswers {
            repairCount++
            LevelNameRepairStat(0, 0, 0, 0)
        }

        val task = ArkLevelSyncTask(service)
        assertTrue(task.updateOpenStatus(), "首次触发应被允许")
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "首次运行应已进入")

        // 互斥标志生效：同一次执行尚未结束时，第二次触发不应启动
        assertEquals(false, task.updateOpenStatus(), "运行中不应再次触发")

        release.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && repairCount == 0) Thread.sleep(20)
        assertEquals(1, repairCount, "回填只应执行一次")
    }
}
