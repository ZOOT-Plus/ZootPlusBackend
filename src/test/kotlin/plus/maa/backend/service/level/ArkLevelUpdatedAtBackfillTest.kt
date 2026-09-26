package plus.maa.backend.service.level

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.common.utils.converter.ArkLevelConverter
import plus.maa.backend.common.utils.converter.ArkLevelEntityConverter
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.repository.GithubRepository
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.github.GithubCommit
import plus.maa.backend.repository.entity.github.GithubTree
import plus.maa.backend.repository.entity.github.GithubTrees
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import java.time.LocalDateTime

/**
 * 存量行 `updated_at` 回填（[ArkLevelService.backfillUpdatedAt]）的行为测试。
 *
 * 真实 embedded PG + 真实 repository；GitHub API 用 mockk 桩（[GithubRepository]）。
 * 只桩 `getTrees` 与 `getCommitsOfPath`——回填**不应**下载任何地图文件，测试也不提供下载路径。
 *
 * 桩里的目录结构与 `maa-copilot.github.tilePosPath` 的默认值（resource/Arknights-Tile-Pos）一致：
 * 回填要先取边界 commit 的根 tree，再逐级下钻两级到地图目录。
 */
class ArkLevelUpdatedAtBackfillTest : TestDbSupport() {

    private val repository = ArkLevelRepository(jdbi)
    private val githubRepo = mockk<GithubRepository>(relaxed = true)
    private val service = ArkLevelService(
        properties = MaaCopilotProperties(),
        githubRepo = githubRepo,
        redisCache = mockk<RedisCache>(relaxed = true),
        arkLevelRepo = repository,
        json = defaultJson,
        arkLevelConverter = mockk<ArkLevelConverter>(relaxed = true),
        arkLevelEntityConverter = ArkLevelEntityConverter(),
    )

    private val properties = MaaCopilotProperties()

    /** 边界 commit 的 sha 与逐级 tree 的 sha（值本身无意义，只用于串起 mock 的调用链）。 */
    private val boundaryCommit = GithubCommit(sha = "boundary-commit")
    private val resourceTreeSha = "tree-resource"
    private val tilePosTreeSha = "tree-tile-pos"

    private fun tree(path: String, sha: String, type: String = "blob") = GithubTree(
        path = path,
        mode = "100644",
        type = type,
        sha = sha,
        url = "https://api.github.com/placeholder/$path",
    )

    /**
     * 桩：边界时刻的地图目录里有 [insert] 产出的 `old` 行对应的上游文件，没有 `new` 行对应的文件。
     * 文件名须按上游命名规则构造（`{stageId}-{levelId 中 / 替换为 -}.json`，如
     * `old-activities-act-old.json`），回填按文件名判定行的窗口归属。
     *
     * @param filePaths 边界时刻地图目录下的文件清单
     */
    private fun stubBoundaryTrees(filePaths: List<String> = listOf("old-activities-act-old.json")) {
        coEvery { githubRepo.getCommitsOfPath(any(), any(), any()) } returns listOf(boundaryCommit)
        coEvery { githubRepo.getTrees(any(), boundaryCommit.sha) } returns GithubTrees(
            sha = boundaryCommit.sha,
            url = "https://api.github.com/placeholder/root",
            tree = listOf(tree("resource", resourceTreeSha, type = "tree")),
        )
        coEvery { githubRepo.getTrees(any(), resourceTreeSha) } returns GithubTrees(
            sha = resourceTreeSha,
            url = "https://api.github.com/placeholder/resource",
            tree = listOf(tree("Arknights-Tile-Pos", tilePosTreeSha, type = "tree")),
        )
        coEvery { githubRepo.getTrees(any(), tilePosTreeSha) } returns GithubTrees(
            sha = tilePosTreeSha,
            url = "https://api.github.com/placeholder/tile-pos",
            tree = filePaths.mapIndexed { index, path -> tree(path, "blob-sha-$index") },
        )
    }

    private fun insert(
        stageId: String?,
        sha: String,
        updatedAt: LocalDateTime? = null,
        catOne: String = ArkLevelType.ACTIVITIES.display,
    ): ArkLevelEntity = repository.insertEntity(
        ArkLevelEntity(
            levelId = "activities/act/$stageId", stageId = stageId, sha = sha,
            catOne = catOne, catTwo = "活动", catThree = "C-1", name = "关卡",
            width = 1, height = 1, updatedAt = updatedAt,
        ),
    )

    @Test
    fun splitsRowsIntoWindowAndOutOfWindowByFilePresence() = runTest {
        stubBoundaryTrees()
        val old = insert(stageId = "old", sha = "blob-sha-0")
        val fresh = insert(stageId = "new", sha = "blob-sha-99")

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(2, stat.scanned)
        assertEquals(1, stat.inWindow, "文件名不在边界 tree 里的行 = 窗口内新增")
        assertEquals(1, stat.outOfWindow)
        assertEquals(2, stat.written)

        val windowStart = ArkLevelV2Service.liteWindowStart()
        val freshUpdatedAt = repository.findById(fresh.id)!!.updatedAt!!
        val oldUpdatedAt = repository.findById(old.id)!!.updatedAt!!
        assertTrue(freshUpdatedAt >= windowStart, "窗口内的行必须落在 lite 窗口内，实测 $freshUpdatedAt")
        assertTrue(oldUpdatedAt < windowStart, "窗口外的行必须落在窗口外，实测 $oldUpdatedAt")
        // 该行随后会被 lite 查询命中，端到端验证一次
        assertEquals(setOf(fresh.id), repository.findAllUpdatedSince(ArkLevelType.ACTIVITIES.display, windowStart).map { it.id }.toSet())
    }

    @Test
    fun staleRowOfAModifiedFileStaysOutOfWindow() = runTest {
        // 同步对每个新 blob sha 都插入新行且不删旧行，所以「边界前被修改过的文件」在库里有同一路径的
        // 两个版本行：旧版本行的 sha 不在边界树中（树里只有修改后的 sha），但该关卡在边界时刻早已
        // 存在。按 sha 判定会把旧行误判为窗口内新增、永久泄漏进 lite（上游实测 2025-06~2026-06 间
        // 有 17 个活动地图文件在边界前被修改）；必须按文件名判定。
        stubBoundaryTrees() // 边界树里 old 文件的 sha = blob-sha-0
        val staleRow = insert(stageId = "old", sha = "sha-before-modification")
        val currentRow = insert(stageId = "old", sha = "blob-sha-0")

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(0, stat.inWindow, "修改前版本的旧行不是新增关卡，不得进入 lite")
        assertEquals(2, stat.outOfWindow)
        val windowStart = ArkLevelV2Service.liteWindowStart()
        assertTrue(repository.findById(staleRow.id)!!.updatedAt!! < windowStart, "旧行必须落在窗口外")
        assertTrue(repository.findById(currentRow.id)!!.updatedAt!! < windowStart)
        assertEquals(
            emptySet<Long>(),
            repository.findAllUpdatedSince(ArkLevelType.ACTIVITIES.display, windowStart).map { it.id }.toSet(),
            "lite 不得包含修改前的旧版本行",
        )
    }

    @Test
    fun rowsWithoutUpstreamFileNameFallOutOfWindow() = runTest {
        // stageId/levelId 缺失的行构造不出上游文件名，无法判定窗口归属：按「宁可少返」口径判窗口外，
        // 而不是抛异常或猜测窗口内
        stubBoundaryTrees()
        val row = repository.insertEntity(
            ArkLevelEntity(
                levelId = null, stageId = null, sha = "sha-x",
                catOne = ArkLevelType.ACTIVITIES.display, catTwo = "活动", catThree = "C-1", name = "关卡",
                width = 1, height = 1, updatedAt = null,
            ),
        )

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(1, stat.outOfWindow)
        assertEquals(0, stat.inWindow)
        assertTrue(
            repository.findById(row.id)!!.updatedAt!! < ArkLevelV2Service.liteWindowStart(),
            "无法定位文件的行落在窗口外",
        )
    }

    @Test
    fun keepsRowsThatAlreadyHaveUpdatedAt() = runTest {
        stubBoundaryTrees()
        val existing = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0)
        val filled = insert(stageId = "old", sha = "blob-sha-0", updatedAt = existing)
        val blank = insert(stageId = "new", sha = "blob-sha-99")

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(1, stat.scanned, "只有 NULL 行进入回填")
        assertEquals(existing, repository.findById(filled.id)!!.updatedAt, "已有值不被改写")
        assertNotNull(repository.findById(blank.id)!!.updatedAt)
    }

    @Test
    fun noPendingRowMeansNoApiCall() = runTest {
        insert(stageId = "old", sha = "blob-sha-0", updatedAt = LocalDateTime.now())

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(UpdatedAtBackfillStat(), stat)
        coVerify(exactly = 0) { githubRepo.getCommitsOfPath(any(), any(), any()) }
        coVerify(exactly = 0) { githubRepo.getTrees(any(), any()) }
    }

    @Test
    fun isIdempotentAcrossRepeatedRuns() = runTest {
        stubBoundaryTrees()
        insert(stageId = "old", sha = "blob-sha-0")
        insert(stageId = "new", sha = "blob-sha-99")

        val first = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)
        val second = service.backfillUpdatedAt(UpdatedAtBackfillSource.DAILY)

        assertEquals(2, first.written)
        assertEquals(0, second.scanned, "第二次已无待回填行")
        // 第二次不再调 API（门禁在 COUNT 之后、触网之前）
        coVerify(exactly = 1) { githubRepo.getCommitsOfPath(any(), any(), any()) }
    }

    @Test
    fun keepsRowsNullWhenGithubIsUnavailable() = runTest {
        coEvery { githubRepo.getCommitsOfPath(any(), any(), any()) } throws IllegalStateException("github down")
        val row = insert(stageId = "old", sha = "blob-sha-0")

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(1, stat.scanned)
        assertEquals(0, stat.written)
        assertNull(repository.findById(row.id)!!.updatedAt, "失败时行保持 NULL，留待下次执行")
    }

    @Test
    fun keepsRowsNullWhenBoundaryCommitIsMissing() = runTest {
        coEvery { githubRepo.getCommitsOfPath(any(), any(), any()) } returns emptyList()
        val row = insert(stageId = "old", sha = "blob-sha-0")

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(1, stat.scanned)
        assertEquals(0, stat.written)
        assertNull(repository.findById(row.id)!!.updatedAt)
    }

    @Test
    fun doesNotOverwriteRowWrittenByConcurrentSync() = runTest {
        // 并发场景：回填读到 NULL 行之后、写入之前，同步任务把该行 upsert 成了真实时刻。
        // 用 mock 在 findAllNullUpdatedAt 的返回点上插入这次并发写，构造出确定性的交错——
        // 条件更新（updated_at IS NULL）必须挡住覆盖，否则同步刚写入的真实时刻会被回填值盖掉。
        stubBoundaryTrees()
        val row = insert(stageId = "old", sha = "blob-sha-0")
        val concurrent = LocalDateTime.of(2026, 9, 25, 3, 0, 0)
        val racing = mockk<ArkLevelRepository> {
            every { countNullUpdatedAt() } answers { repository.countNullUpdatedAt() }
            every { findAllNullUpdatedAt() } answers {
                // 先按真实语义读出待回填行，再模拟另一路写者抢先落库
                val blanks = repository.findAllNullUpdatedAt()
                repository.updateUpdatedAtByIds(listOf(row.id to concurrent))
                blanks
            }
            every { updateUpdatedAtByIds(any()) } answers { repository.updateUpdatedAtByIds(firstArg()) }
        }

        val stat = serviceWith(racing).backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(1, stat.scanned)
        assertEquals(0, stat.written, "目标行已被并发写入，条件更新影响 0 行")
        assertEquals(concurrent, repository.findById(row.id)!!.updatedAt, "并发写入的值不被回填覆盖")
    }

    @Test
    fun ignoresNonActivityRowsWhenJudgingNothingButStillFillsThem() = runTest {
        // 回填对所有分类生效（updated_at 是通用列），lite 查询才按分类过滤
        stubBoundaryTrees()
        val mainline = insert(stageId = "main_01-07", sha = "blob-sha-0", catOne = ArkLevelType.MAINLINE.display)
        val freshActivity = insert(stageId = "new", sha = "blob-sha-99")

        service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertNotNull(repository.findById(mainline.id)!!.updatedAt, "非活动关卡也要回填")
        val windowStart = ArkLevelV2Service.liteWindowStart()
        val lite = repository.findAllUpdatedSince(ArkLevelType.ACTIVITIES.display, windowStart)
        assertEquals(listOf(freshActivity.id), lite.map { it.id }, "lite 只含活动关卡")
    }

    @Test
    fun excludesFilteredUpstreamFilesFromBoundarySet() = runTest {
        // 上游目录里有肉鸽/训练/overview 等同步逻辑不会入库的文件；它们即便出现也不该影响判定，
        // 且此处 filePaths 里只有非地图文件时，全部行都应判为窗口内（等价的空边界集）
        stubBoundaryTrees(filePaths = listOf("overview.json", "roguelike/ro1.json", "tr_01.json"))
        val row = insert(stageId = "new", sha = "blob-sha-99")

        val stat = service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        assertEquals(1, stat.inWindow, "被同步逻辑排除的文件不参与判定")
        assertEquals(0, stat.outOfWindow)
        assertNotNull(repository.findById(row.id)!!.updatedAt)
    }

    @Test
    fun usesConfiguredTilePosPathWhenWalkingTrees() = runTest {
        // 逐级下钻依赖 maa-copilot.github.tilePosPath；此处断言默认配置下的两级路径确实被走到，
        // 配置改成一层的自定义值时回填会失败（保持 NULL），而不是静默按错误的目录判定
        stubBoundaryTrees()
        insert(stageId = "old", sha = "blob-sha-0")

        service.backfillUpdatedAt(UpdatedAtBackfillSource.STARTUP)

        coVerify(exactly = 1) { githubRepo.getTrees(properties.github.token, boundaryCommit.sha) }
        coVerify(exactly = 1) { githubRepo.getTrees(properties.github.token, resourceTreeSha) }
        coVerify(exactly = 1) { githubRepo.getTrees(properties.github.token, tilePosTreeSha) }
    }

    /** 用给定 repository 构造一个与本类同构的 service（其余依赖与 [service] 一致）。 */
    private fun serviceWith(repository: ArkLevelRepository): ArkLevelService = ArkLevelService(
        properties = MaaCopilotProperties(),
        githubRepo = githubRepo,
        redisCache = mockk<RedisCache>(relaxed = true),
        arkLevelRepo = repository,
        json = defaultJson,
        arkLevelConverter = mockk<ArkLevelConverter>(relaxed = true),
        arkLevelEntityConverter = ArkLevelEntityConverter(),
    )
}
