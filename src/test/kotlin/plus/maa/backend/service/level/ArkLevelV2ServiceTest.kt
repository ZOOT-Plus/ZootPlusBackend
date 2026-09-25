package plus.maa.backend.service.level

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import java.time.LocalDateTime

/**
 * [ArkLevelV2Service] 的行为测试（真实 embedded PG + 真实 repository，无 Spring 上下文）。
 *
 * 直接构造服务实例，因此 `@Cacheable` 不生效——缓存行为不是本测试的对象，这里验证的是
 * 「版本号与行数据来自同一次计算」这条正确性约束（缓存只会让它更强）。
 */
class ArkLevelV2ServiceTest : TestDbSupport() {

    private val repository = ArkLevelRepository(jdbi)
    private val service = ArkLevelV2Service(repository)

    private fun insert(
        levelId: String? = "activities/act1dp/level_act1dp_01",
        stageId: String? = "act1dp_01",
        catOne: String? = ArkLevelType.ACTIVITIES.display,
        catTwo: String? = "登临意",
        catThree: String? = "DP-1",
        name: String? = "登临意",
        width: Int = 9,
        height: Int = 6,
        isOpen: Boolean? = null,
        closeTime: LocalDateTime? = null,
        updatedAt: LocalDateTime? = LocalDateTime.now(),
    ): ArkLevelEntity = repository.insertEntity(
        ArkLevelEntity(
            levelId = levelId, stageId = stageId, sha = "sha-$stageId",
            catOne = catOne, catTwo = catTwo, catThree = catThree, name = name,
            width = width, height = height, isOpen = isOpen, closeTime = closeTime, updatedAt = updatedAt,
        ),
    )

    private fun versionOf(lite: Boolean = false, withSize: Boolean = false) = service.payload(lite = lite, withSize = withSize).version

    // ------------------------------------------------------------------ 变体与版本号

    @Test
    fun withSizeSharesVersionWithNoSizeVariant() {
        insert()
        insert(levelId = "activities/act1dp/level_act1dp_02", stageId = "act1dp_02")

        assertEquals(versionOf(withSize = false), versionOf(withSize = true), "withSize 不改变行集合，应共用版本号")
        assertEquals(
            service.payload(lite = false, withSize = false).levels.size,
            service.payload(lite = false, withSize = true).levels.size,
        )
    }

    @Test
    fun liteVersionIsIndependentFromFull() {
        // 只有一个 3 个月前的活动关卡：full 有它、lite 没有，两个版本号必须不同
        insert(updatedAt = LocalDateTime.now().minusMonths(6))

        assertNotEquals(versionOf(lite = false), versionOf(lite = true))
        assertEquals(1, service.payload(lite = false, withSize = false).levels.size)
        assertEquals(0, service.payload(lite = true, withSize = false).levels.size)
    }

    @Test
    fun versionIsStableAcrossCallsWhenNothingChanges() {
        insert()
        insert(stageId = "act1dp_02", levelId = "activities/act1dp/level_act1dp_02")

        // 顺序确定性：两次查询必须给出同一顺序、同一摘要（SQL 无 ORDER BY 时 PG 可能换序）
        assertEquals(versionOf(), versionOf())
    }

    @Test
    fun versionChangesWhenRowContentChanges() {
        val row = insert()
        val before = versionOf()

        repository.saveAll(listOf(repository.findById(row.id)!!.apply { name = "改过的名字" }))

        assertNotEquals(before, versionOf(), "字段变化必须改变版本号（否则客户端永远拿旧值）")
    }

    @Test
    fun versionChangesWhenOpenStatusFlipsInPlace() {
        // 开放状态跑批原地改写 is_open/close_time 而不产生新行——仅用行数或最大 id 做版本号会漏掉这类变化
        val row = insert(isOpen = true)
        val before = versionOf()

        repository.updateCatTwoByIds(emptyList()) // 无关操作，确保不是它带来的变化
        repository.saveAll(listOf(repository.findById(row.id)!!.apply { isOpen = false }))
        assertNotEquals(before, versionOf(), "is_open 原地变化必须改变版本号")

        val afterOpen = versionOf()
        repository.saveAll(
            listOf(repository.findById(row.id)!!.apply { closeTime = LocalDateTime.of(2026, 1, 1, 0, 0, 0) }),
        )
        assertNotEquals(afterOpen, versionOf(), "close_time 原地变化必须改变版本号")
    }

    @Test
    fun versionIgnoresUpdatedAtChanges() {
        // updated_at 不参与响应内容；存量回填会一次性改动全部行，算进去只会让所有客户端白失效一次
        val row = insert()
        val before = versionOf()

        repository.updateUpdatedAtByIds(listOf(row.id to LocalDateTime.of(2030, 1, 1, 0, 0, 0)))

        assertEquals(before, versionOf(), "updated_at 不进入摘要")
    }

    @Test
    fun versionDistinguishesFieldBoundaries() {
        // 分隔符的必要性：不设分隔符时 ("ab","c") 与 ("a","bc") 会拼成同一段文本 → 同一版本号
        insert(levelId = "ab", stageId = "c")
        val before = versionOf()

        repository.deleteById(repository.findAllOrdered().single().id)
        insert(levelId = "a", stageId = "bc")

        assertNotEquals(before, versionOf(), "字段拼接必须可无歧义还原")
    }

    @Test
    fun emptyTableHasWellFormedVersion() {
        val payload = service.payload(lite = false, withSize = false)

        assertEquals(32, payload.version.length)
        assertEquals(Regex("^[0-9a-f]{32}$").matches(payload.version), true)
        assertEquals(emptyList<Any>(), payload.levels)
    }

    // ------------------------------------------------------------------ 行集合

    @Test
    fun liteIncludesOnlyRecentActivityLevels() {
        // 服务每次查询现算窗口起点，故边界用 ±1 分钟留出余量（单个用例耗时在秒级）。
        // 边界的精确语义（>= 含等于）由 ArkLevelRepositoryTest.findAllUpdatedSinceFiltersByCatOneAndWindow 锁死
        val start = ArkLevelV2Service.liteWindowStart()
        val recent = insert(levelId = "act-new", stageId = "act_new", updatedAt = LocalDateTime.now().minusDays(1))
        val nearBoundary = insert(levelId = "act-edge", stageId = "act_edge", updatedAt = start.plusMinutes(1))
        insert(levelId = "act-old", stageId = "act_old", updatedAt = start.minusMinutes(1))
        insert(levelId = "main-new", stageId = "main_new", catOne = "主题曲", updatedAt = LocalDateTime.now())
        insert(levelId = "legion-new", stageId = "legion_new", catOne = "保全派驻", updatedAt = LocalDateTime.now())
        insert(levelId = "act-null", stageId = "act_null", updatedAt = null)

        val lite = service.payload(lite = true, withSize = false).levels
        assertEquals(setOf(recent.levelId, nearBoundary.levelId), lite.map { it.levelId }.toSet())
        assertEquals(6, service.payload(lite = false, withSize = false).levels.size, "full 变体不受窗口与分类影响")
    }

    @Test
    fun rowsAreOrderedByStageIdThenId() {
        insert(levelId = "b", stageId = "st-2")
        insert(levelId = "a", stageId = "st-1")
        insert(levelId = "c", stageId = "st-2")

        assertEquals(listOf("st-1", "st-2", "st-2"), service.payload(lite = false, withSize = false).levels.map { it.stageId })
    }

    // ------------------------------------------------------------------ DTO 映射

    @Test
    fun noSizeVariantDropsWidthHeight() {
        insert(width = 12, height = 8)

        val row = service.payload(lite = false, withSize = false).levels.single()
        assertNull(row.width)
        assertNull(row.height)

        val sized = service.payload(lite = false, withSize = true).levels.single()
        assertEquals(12, sized.width)
        assertEquals(8, sized.height)
    }

    @Test
    fun nullableColumnsDegradeToEmptyStringLikeV1() {
        // 与 v1 的 ArkLevelConverter 保持同一口径：库里可空的列在响应里是空串，不是 null
        val row = insert(levelId = "act-1", catTwo = null, catThree = null, name = null)

        val dto = service.payload(lite = false, withSize = false).levels.single()
        assertEquals("", dto.catTwo)
        assertEquals("", dto.catThree)
        assertEquals("", dto.name)
        assertEquals("act-1", dto.levelId)
        assertEquals(row.stageId, dto.stageId)
    }

    @Test
    fun versionTreatsNullAndEmptyStringAsTheSameContent() {
        // 空串与 NULL 在 DTO 映射里都退化为 ""（与 v1 的 ArkLevelConverter 同口径），故对响应内容等价，
        // 版本号也不应区分——否则这类无感差异会让所有客户端白重下一次。
        // 承诺的是「响应体相同 ⇒ 版本号相同」，不是「库里的字节相同」。
        val a = insert(levelId = "act-1", catTwo = null, stageId = "st-1")
        val v1 = versionOf()
        repository.saveAll(listOf(repository.findById(a.id)!!.apply { catTwo = "" }))

        assertEquals(v1, versionOf(), "NULL 与空串对响应内容等价，不必让全量客户端重下")
    }
}
