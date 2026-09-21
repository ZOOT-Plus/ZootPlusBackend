package plus.maa.backend.service.level

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.common.utils.converter.ArkLevelConverter
import plus.maa.backend.common.utils.converter.ArkLevelEntityConverter
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.repository.GithubRepository
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.gamedata.ArkActivity
import plus.maa.backend.repository.entity.gamedata.ArkStage
import plus.maa.backend.repository.ktorm.ArkLevelRepository

/**
 * 活动名回填（[ArkLevelService.repairMissingActivityNames]）行为测试。
 *
 * 真实 embedded PG + 真实 [ArkLevelRepository]；游戏数据快照用内存构造的 [ArkGameDataHolder]
 * 桩（`internal constructor`），全程不触网。
 *
 * 未覆盖点：抓取远端快照（[ArkGameDataHolder.fetch]）本身、以及只有真 Redis 才能验的过期语义
 * （[RedisCache.getCache] 是 inline 函数，mock 只能替换其内部的 `redisTemplate`）。
 */
@OptIn(RedisCache.RedisCacheInternalApi::class)
class ArkLevelNameRepairTest : TestDbSupport() {

    private val repository = ArkLevelRepository(jdbi)
    private val redisCache = mockk<RedisCache>(relaxed = true)
    private val service = ArkLevelService(
        properties = MaaCopilotProperties(),
        githubRepo = mockk<GithubRepository>(relaxed = true),
        redisCache = redisCache,
        arkLevelRepo = repository,
        json = defaultJson,
        arkLevelConverter = mockk<ArkLevelConverter>(relaxed = true),
        arkLevelEntityConverter = ArkLevelEntityConverter(),
    )

    /** 桩快照：activities/act1dp/level_act1dp_01 → act1dp_zone1 → [activityName]。 */
    private fun holder(activityName: String = "登临意"): ArkGameDataHolder = ArkGameDataHolder(
        stageMap = mapOf(
            "act1dp_01" to ArkStage(
                levelId = "activities/act1dp/level_act1dp_01",
                zoneId = "act1dp_zone1",
                stageId = "act1dp_01",
                code = "DP-1",
            ),
        ),
        zoneMap = emptyMap(),
        zoneActivityMap = mapOf("act1dp_zone1" to ArkActivity(id = "act1dp", name = activityName)),
        arkCharacterMap = emptyMap(),
        arkTowerMap = emptyMap(),
        arkCrisisV2InfoMap = emptyMap(),
    )

    /** 空快照：模拟上游缺失该活动的 stage/activity 数据（如 act38rune）。 */
    private fun emptyHolder(): ArkGameDataHolder = ArkGameDataHolder(
        stageMap = emptyMap(),
        zoneMap = emptyMap(),
        zoneActivityMap = emptyMap(),
        arkCharacterMap = emptyMap(),
        arkTowerMap = emptyMap(),
        arkCrisisV2InfoMap = emptyMap(),
    )

    private fun insertLevel(
        levelId: String? = "activities/act1dp/level_act1dp_01",
        stageId: String? = "act1dp_01",
        catOne: String? = ArkLevelType.ACTIVITIES.display,
        catTwo: String? = "",
        catThree: String? = "DP-1",
    ): ArkLevelEntity = repository.insertEntity(
        ArkLevelEntity(
            levelId = levelId,
            stageId = stageId,
            sha = "sha-1",
            catOne = catOne,
            catTwo = catTwo,
            catThree = catThree,
            name = "关卡名",
            width = 9,
            height = 6,
        ),
    )

    @Test
    fun repairFillsBlankActivityName() = runTest {
        val row = insertLevel()

        val stat = service.repairMissingActivityNames(holder())

        assertEquals(1, stat.scanned)
        assertEquals(1, stat.repaired)
        assertEquals(0, stat.skipped)
        assertEquals(0, stat.stillEmpty)
        assertEquals("登临意", repository.findById(row.id)!!.catTwo)
    }

    @Test
    fun repairIsIdempotent() = runTest {
        insertLevel()

        val first = service.repairMissingActivityNames(holder())
        val second = service.repairMissingActivityNames(holder())

        assertEquals(1, first.repaired)
        assertEquals(0, second.repaired, "重复执行第二次应为 0 变更")
        assertEquals(0, second.scanned, "已修复的行不再命中空值查询")
        assertEquals(1, repository.count(), "回填不新增行")
    }

    @Test
    fun repairKeepsUnresolvableRowBlank() = runTest {
        // 上游数据缺失：解析出的 cat_two 仍是空串，此时不写库、不抛异常，留待下一轮重试
        val row = insertLevel(levelId = "activities/act38rune/level_act38rune_01", stageId = "act38rune_01", catThree = "R-1")

        val stat = service.repairMissingActivityNames(emptyHolder())

        assertEquals(1, stat.scanned)
        assertEquals(0, stat.repaired)
        assertEquals(1, stat.stillEmpty)
        assertEquals("", repository.findById(row.id)!!.catTwo)
    }

    @Test
    fun repairIgnoresNonActivityCategories() = runTest {
        // 主题曲/剿灭/悖论模拟等分类的 parser 会覆写 cat_three，不能用 cat_three 重建地图文件，
        // 因此回填范围必须严格限定在「活动关卡」（见方案 §6.1）
        val mainline = insertLevel(
            levelId = "obt/main/level_main_01-07",
            stageId = "main_01-07",
            catOne = ArkLevelType.MAINLINE.display,
            catTwo = null,
            catThree = "1-7",
        )
        val legion = insertLevel(
            levelId = "obt/legion/lt06/level_lt06_01",
            stageId = "lt06_01",
            catOne = ArkLevelType.LEGION.display,
            catTwo = null,
            catThree = "LT-1",
        )

        val stat = service.repairMissingActivityNames(holder())

        assertEquals(0, stat.scanned)
        assertEquals(0, stat.repaired)
        assertEquals(null, repository.findById(mainline.id)!!.catTwo)
        assertEquals(null, repository.findById(legion.id)!!.catTwo)
    }

    @Test
    fun repairFallsBackToStageIdWhenCatThreeIsNull() = runTest {
        // cat_three 可空（见「所有关卡code可空处理」）：code 对不上时 findStage 退回按 stage_id 查找
        val row = insertLevel(catThree = null)

        service.repairMissingActivityNames(holder())

        assertEquals("登临意", repository.findById(row.id)!!.catTwo)
    }

    @Test
    fun repairContinuesAfterMalformedRow() = runTest {
        // 历史脏数据可能让 parser 的空断言失败（如 stage_id 缺失）；单行异常不应中断整批回填
        val broken = insertLevel(stageId = null, catThree = null)
        val healthy = insertLevel()

        val stat = service.repairMissingActivityNames(holder())

        assertEquals(2, stat.scanned)
        assertEquals(1, stat.repaired)
        assertEquals(1, stat.stillEmpty)
        assertEquals("", repository.findById(broken.id)!!.catTwo)
        assertEquals("登临意", repository.findById(healthy.id)!!.catTwo)
    }

    @Test
    fun repairCountsLostRaceAsSkipped() = runTest {
        // 「查询到空值行之后、写入之前，该行已被并发写入填好」这一交错无法用真实 DB 确定性地
        // 构造，故用 mock 注入：条件更新影响 0 行时应计为 skipped，而不是 repaired。
        val racedRepository = mockk<ArkLevelRepository> {
            every { findAllBlankCatTwoByCatOne(any()) } returns listOf(
                ArkLevelEntity(
                    id = 7,
                    levelId = "activities/act1dp/level_act1dp_01",
                    stageId = "act1dp_01",
                    catOne = ArkLevelType.ACTIVITIES.display,
                    catTwo = "",
                    catThree = "DP-1",
                ),
            )
            every { updateCatTwoById(7L, "登临意") } returns 0
        }
        val racedService = ArkLevelService(
            properties = MaaCopilotProperties(),
            githubRepo = mockk<GithubRepository>(relaxed = true),
            redisCache = mockk<RedisCache>(relaxed = true),
            arkLevelRepo = racedRepository,
            json = defaultJson,
            arkLevelConverter = mockk<ArkLevelConverter>(relaxed = true),
            arkLevelEntityConverter = ArkLevelEntityConverter(),
        )

        val stat = racedService.repairMissingActivityNames(holder())

        assertEquals(1, stat.scanned)
        assertEquals(0, stat.repaired)
        assertEquals(1, stat.skipped, "影响 0 行 = 竞争失败，值已被他人填好")
        assertEquals(0, stat.stillEmpty)
    }

    @Test
    fun gateStopsBeforeSnapshotWhenNoActivityRowIsBlank() = runTest {
        // 门禁统计的是「活动关卡」的空名行：其它分类全空也不该触发回填。
        // 若门禁口径写错（例如漏掉 cat_one 条件），就会走到取快照（触网）并在失败后返回
        // scanned = 空值行数 —— 断言 stat 全零即可捕获该回归。
        val mainline = insertLevel(
            levelId = "obt/main/level_main_01-07",
            stageId = "main_01-07",
            catOne = ArkLevelType.MAINLINE.display,
            catTwo = null,
            catThree = "1-7",
        )

        val stat = service.repairMissingActivityNames(LevelNameRepairSource.STARTUP)

        assertEquals(0, stat.scanned)
        assertEquals(0, stat.repaired)
        assertEquals(0, stat.stillEmpty)
        assertEquals(null, repository.findById(mainline.id)!!.catTwo)
    }

    @Test
    fun startupRepairIsSkippedWithinThrottleWindow() = runTest {
        val row = insertLevel()
        // 模拟 Redis 中已存在上一次执行的记录。getCache/setCache 是 inline 函数无法直接 mock，
        // 只能替换其内部使用的 redisTemplate（故需要 opt-in 到 RedisCache 的内部 API）。
        val values = mockk<ValueOperations<String, String>>()
        every { values.get(any<String>()) } returns "\"1\""
        val redisTemplate = mockk<StringRedisTemplate>()
        every { redisTemplate.opsForValue() } returns values
        every { redisCache.redisTemplate } returns redisTemplate

        val stat = service.repairMissingActivityNames(LevelNameRepairSource.STARTUP)

        assertEquals(0, stat.repaired)
        assertEquals("", repository.findById(row.id)!!.catTwo, "节流窗口内不执行回填")
    }
}
