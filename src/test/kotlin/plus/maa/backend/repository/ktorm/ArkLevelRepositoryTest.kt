package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.ArkLevelEntity
import java.time.LocalDateTime

/**
 * ArkLevelRepository 基线集成测试。
 *
 * 覆盖方法：findByStageId / findAllByStageIds / findByLevelId / findAllOpenLevels / insertEntity /
 * findById / deleteById / existsById / save / findByLevelIdFuzzy / queryLevelByKeyword /
 * findAllShaBy / findAllByCatOne / saveAll / count（继承）。
 *
 * 未覆盖点与基线记录：
 * - 唯一约束冲突：ark_level 表（V1__init.sql）无任何唯一约束（level_id/stage_id/name 均为普通索引），
 *   该通用场景不适用；基线行为 = 重复 level_id 可共存（见 insertDuplicateLevelIdAllowed）。
 * - findByLevelIdFuzzy 的 keyword 含 `%`/`_` 时按 LIKE 通配符解释，无转义（见
 *   findByLevelIdFuzzyWildcardBaseline）。
 */
class ArkLevelRepositoryTest : TestDbSupport() {

    private val repository = ArkLevelRepository(jdbi)

    /** 工厂：不设置 id（data class 默认 0L → INSERT 不包含 id 列 → 自增回填）。 */
    private fun newLevel(
        levelId: String? = null,
        stageId: String? = null,
        sha: String = "sha",
        catOne: String? = null,
        catTwo: String? = null,
        catThree: String? = null,
        name: String? = null,
        width: Int = 0,
        height: Int = 0,
        isOpen: Boolean? = null,
        closeTime: LocalDateTime? = null,
    ): ArkLevelEntity = ArkLevelEntity(
        levelId = levelId,
        stageId = stageId,
        sha = sha,
        catOne = catOne,
        catTwo = catTwo,
        catThree = catThree,
        name = name,
        width = width,
        height = height,
        isOpen = isOpen,
        closeTime = closeTime,
    )

    // ------------------------------------------------------------------ findByStageId

    @Test
    fun findByStageIdHitReturnsEntity() {
        repository.insertEntity(newLevel(levelId = "lv-1", stageId = "st-1", name = "关卡一"))
        repository.insertEntity(newLevel(levelId = "lv-2", stageId = "st-2", name = "关卡二"))

        val hit = repository.findByStageId("st-1")
        assertNotNull(hit)
        assertEquals("lv-1", hit!!.levelId)
        assertEquals("关卡一", hit.name)
    }

    @Test
    fun findByStageIdMissReturnsNull() {
        repository.insertEntity(newLevel(levelId = "lv-1", stageId = "st-1"))
        assertNull(repository.findByStageId("not-exist"))
        assertNull(repository.findByStageId(""))
    }

    @Test
    fun findByStageIdNullStageRowNotMatched() {
        // stage_id 为 NULL 的行不应被任何具体值命中
        repository.insertEntity(newLevel(levelId = "lv-null", stageId = null))
        assertNull(repository.findByStageId("st-1"))
    }

    @Test
    fun findByStageIdMultipleSameStageReturnsOneOfThem() {
        val a = repository.insertEntity(newLevel(levelId = "lv-a", stageId = "dup"))
        val b = repository.insertEntity(newLevel(levelId = "lv-b", stageId = "dup"))

        val hit = repository.findByStageId("dup")
        assertNotNull(hit)
        assertTrue(hit!!.id == a.id || hit.id == b.id, "应返回同 stage_id 两行中的一行")
        assertEquals("dup", hit.stageId)
        assertEquals(2, repository.findAllByStageIds(listOf("dup")).size)
    }

    // ------------------------------------------------------------------ findAllByStageIds

    @Test
    fun findAllByStageIdsPartialHit() {
        repository.insertEntity(newLevel(levelId = "lv-1", stageId = "a"))
        repository.insertEntity(newLevel(levelId = "lv-2", stageId = "b"))
        repository.insertEntity(newLevel(levelId = "lv-3", stageId = "c"))

        val result = repository.findAllByStageIds(listOf("a", "c"))
        assertEquals(2, result.size)
        assertEquals(setOf("lv-1", "lv-3"), result.map { it.levelId }.toSet())
    }

    @Test
    fun findAllByStageIdsFullHitAndEmptyResult() {
        repository.insertEntity(newLevel(levelId = "lv-1", stageId = "a"))
        repository.insertEntity(newLevel(levelId = "lv-2", stageId = "b"))

        assertEquals(setOf("lv-1", "lv-2"), repository.findAllByStageIds(listOf("a", "b")).map { it.levelId }.toSet())
        assertTrue(repository.findAllByStageIds(listOf("not-exist")).isEmpty())
    }

    @Test
    fun findAllByStageIdsNullStageNotMatched() {
        repository.insertEntity(newLevel(levelId = "lv-null", stageId = null))
        assertTrue(repository.findAllByStageIds(listOf("a")).isEmpty())
    }

    @Test
    fun findByStageIdsEmptyListReturnsEmpty() {
        repository.insertEntity(newLevel(levelId = "lv-1", stageId = "a"))
        assertTrue(repository.findAllByStageIds(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------ findByLevelId

    @Test
    fun findByLevelIdHitAndMiss() {
        repository.insertEntity(newLevel(levelId = "lv-1", stageId = "st-1"))
        assertNotNull(repository.findByLevelId("lv-1"))
        assertEquals("lv-1", repository.findByLevelId("lv-1")!!.levelId)
        assertNull(repository.findByLevelId("not-exist"))
        assertNull(repository.findByLevelId(""))
    }

    @Test
    fun findByLevelIdNullLevelRowNotMatched() {
        repository.insertEntity(newLevel(levelId = null, stageId = "st-1"))
        assertNull(repository.findByLevelId("lv-1"))
    }

    // ------------------------------------------------------------------ findAllOpenLevels

    @Test
    fun findAllOpenLevelsReturnsOnlyTrue() {
        repository.insertEntity(newLevel(levelId = "open-1", isOpen = true))
        repository.insertEntity(newLevel(levelId = "open-2", isOpen = true))
        repository.insertEntity(newLevel(levelId = "closed-1", isOpen = false))
        repository.insertEntity(newLevel(levelId = "null-1", isOpen = null))

        val result = repository.findAllOpenLevels()
        assertEquals(2, result.size)
        assertEquals(setOf("open-1", "open-2"), result.map { it.levelId }.toSet())
    }

    @Test
    fun findAllOpenLevelsEmptyTable() {
        assertTrue(repository.findAllOpenLevels().isEmpty())
    }

    // ------------------------------------------------------------------ insertEntity

    @Test
    fun insertEntityBackfillsIdAndRoundTripsAllColumns() {
        val closeTime = LocalDateTime.of(2024, 5, 1, 12, 30, 0) // 无纳秒，避免 timestamp(3) 精度差异
        val entity = newLevel(
            levelId = "lv-1", stageId = "st-1", sha = "sha-abc",
            catOne = "c1", catTwo = "c2", catThree = "c3",
            name = "关卡", width = 10, height = 20,
            isOpen = true, closeTime = closeTime,
        )

        repository.insertEntity(entity)
        assertEquals(1L, entity.id, "自增主键应回填为 1")

        val loaded = repository.findById(1L)
        assertNotNull(loaded)
        loaded!!.let {
            assertEquals("lv-1", it.levelId)
            assertEquals("st-1", it.stageId)
            assertEquals("sha-abc", it.sha)
            assertEquals("c1", it.catOne)
            assertEquals("c2", it.catTwo)
            assertEquals("c3", it.catThree)
            assertEquals("关卡", it.name)
            assertEquals(10, it.width)
            assertEquals(20, it.height)
            assertEquals(true, it.isOpen)
            assertEquals(closeTime, it.closeTime)
        }
    }

    @Test
    fun insertEntityConsecutiveIdsIncrement() {
        val a = repository.insertEntity(newLevel(levelId = "lv-1"))
        val b = repository.insertEntity(newLevel(levelId = "lv-2"))
        val c = repository.insertEntity(newLevel(levelId = "lv-3"))
        assertEquals(listOf(1L, 2L, 3L), listOf(a.id, b.id, c.id))
    }

    @Test
    fun insertEntityNullableColumnsRoundTripAsNull() {
        repository.insertEntity(newLevel(levelId = null, stageId = null, name = null, isOpen = null, closeTime = null))

        val loaded = repository.findById(1L)!!
        assertNull(loaded.levelId)
        assertNull(loaded.stageId)
        assertNull(loaded.name)
        assertNull(loaded.isOpen)
        assertNull(loaded.closeTime)
        // 非空列仍按默认值落库
        assertEquals("sha", loaded.sha)
        assertEquals(0, loaded.width)
        assertEquals(0, loaded.height)
    }

    // ------------------------------------------------------------------ findById / deleteById / existsById

    @Test
    fun findByIdHitMissAndZero() {
        repository.insertEntity(newLevel(levelId = "lv-1"))
        assertNotNull(repository.findById(1L))
        assertNull(repository.findById(2L))
        assertNull(repository.findById(0L))
    }

    @Test
    fun deleteByIdAffectedRowsAndIdempotent() {
        repository.insertEntity(newLevel(levelId = "lv-1"))
        repository.insertEntity(newLevel(levelId = "lv-2"))
        repository.insertEntity(newLevel(levelId = "lv-3"))

        assertTrue(repository.deleteById(2L), "删除存在的行应返回 true")
        assertEquals(2, repository.count())
        assertNull(repository.findById(2L))

        assertFalse(repository.deleteById(2L), "重复删除应返回 false")
        assertFalse(repository.deleteById(999L), "删除不存在的 id 应返回 false")
    }

    @Test
    fun deleteByIdSequenceNotRewound() {
        repository.insertEntity(newLevel(levelId = "lv-1"))
        repository.insertEntity(newLevel(levelId = "lv-2"))
        repository.insertEntity(newLevel(levelId = "lv-3"))
        assertTrue(repository.deleteById(2L))

        val fresh = repository.insertEntity(newLevel(levelId = "lv-4"))
        assertEquals(4L, fresh.id, "删除后自增序列不回退")
    }

    @Test
    fun existsByIdTrueAndFalse() {
        repository.insertEntity(newLevel(levelId = "lv-1"))
        assertTrue(repository.existsById(1L))
        assertFalse(repository.existsById(2L))
        assertFalse(repository.existsById(0L))
    }

    // ------------------------------------------------------------------ save

    @Test
    fun saveNewEntityInsertsAndBackfillsId() {
        val entity = newLevel(levelId = "lv-1", name = "新关卡")
        val saved = repository.save(entity)
        assertEquals(1L, saved.id, "id=0 的新实体应插入并回填自增 id")
        assertEquals(1, repository.count())
        assertEquals("新关卡", repository.findById(1L)!!.name)
    }

    @Test
    fun saveExplicitNonExistentIdInsertsAsGiven() {
        val entity = newLevel(levelId = "lv-5").apply { id = 5L }
        val saved = repository.save(entity)
        assertEquals(5L, saved.id)
        assertEquals(5L, repository.findById(5L)!!.id)
        assertEquals(1, repository.count())

        val next = repository.insertEntity(newLevel(levelId = "lv-next"))
        assertEquals(1L, next.id, "显式指定主键插入不推进 bigserial 序列")
        assertEquals(2, repository.count())
    }

    @Test
    fun saveExistingEntityUpdatesWithoutNewRow() {
        repository.insertEntity(newLevel(levelId = "lv-1", name = "旧名"))
        assertEquals(1, repository.count())

        val attached = repository.findById(1L)!!
        attached.name = "新名"
        repository.save(attached)

        assertEquals(1, repository.count(), "已存在实体 save 应更新而非新增行")
        assertEquals("新名", repository.findById(1L)!!.name)
    }

    // ------------------------------------------------------------------ findByLevelIdFuzzy

    @Test
    fun findByLevelIdFuzzySubstringHit() {
        repository.insertEntity(newLevel(levelId = "1-7"))
        repository.insertEntity(newLevel(levelId = "1-7-hard"))
        repository.insertEntity(newLevel(levelId = "main_01-07"))
        repository.insertEntity(newLevel(levelId = "other"))

        val result = repository.findByLevelIdFuzzy("1-7")
        // “main_01-07” 的子串为 “1-0-7”（01-07 中 1 与 7 不相邻），不包含连续子串 “1-7”，
        // LIKE '%1-7%' 正确排除（与 PG LIKE 语义一致）
        assertEquals(setOf("1-7", "1-7-hard"), result.map { it.levelId }.toSet())
    }

    @Test
    fun findByLevelIdFuzzyCaseSensitive() {
        repository.insertEntity(newLevel(levelId = "AbC"))
        assertTrue(repository.findByLevelIdFuzzy("abc").isEmpty(), "PG LIKE 区分大小写")
        assertEquals(listOf("AbC"), repository.findByLevelIdFuzzy("AbC").map { it.levelId })
    }

    @Test
    fun findByLevelIdFuzzyNoMatchEmpty() {
        repository.insertEntity(newLevel(levelId = "lv-1"))
        assertTrue(repository.findByLevelIdFuzzy("不存在").isEmpty())
    }

    @Test
    fun findByLevelIdFuzzyWildcardBaseline() {
        // keyword 未转义，`_`/`%` 按 LIKE 通配符解释
        repository.insertEntity(newLevel(levelId = "a1x7b"))
        // '_' 匹配任意单字符："1x7" 命中 "1_7"
        assertEquals(listOf("a1x7b"), repository.findByLevelIdFuzzy("1_7").map { it.levelId })
        // '%' 匹配任意串：命中全部非 NULL level_id
        assertEquals(listOf("a1x7b"), repository.findByLevelIdFuzzy("%").map { it.levelId })
    }

    // ------------------------------------------------------------------ queryLevelByKeyword

    @Test
    fun queryLevelByKeywordHitsEachColumn() {
        repository.insertEntity(newLevel(name = "关卡A", levelId = "lv-100", stageId = "st-100"))
        repository.insertEntity(newLevel(name = "关卡B", levelId = "lv-200", stageId = "st-300"))

        assertEquals(listOf("关卡A"), repository.queryLevelByKeyword("关卡A").map { it.name })
        assertEquals(listOf("关卡B"), repository.queryLevelByKeyword("lv-200").map { it.name })
        assertEquals(listOf("关卡B"), repository.queryLevelByKeyword("st-300").map { it.name })
    }

    @Test
    fun queryLevelByKeywordTripleHitReturnedOnce() {
        // name / level_id / stage_id 同时命中同一行 → OR 语义，只返回一次
        repository.insertEntity(newLevel(name = "同关键词", levelId = "同关键词", stageId = "同关键词"))
        repository.insertEntity(newLevel(name = "无关", levelId = "lv-2", stageId = "st-2"))

        val result = repository.queryLevelByKeyword("同关键词")
        assertEquals(1, result.size)
        assertEquals("同关键词", result.single().name)
    }

    @Test
    fun queryLevelByKeywordUnrelatedEmpty() {
        repository.insertEntity(newLevel(name = "关卡A", levelId = "lv-100", stageId = "st-100"))
        assertTrue(repository.queryLevelByKeyword("不存在的关键词").isEmpty())
    }

    // ------------------------------------------------------------------ findAllShaBy

    @Test
    fun findAllShaByReturnsAllSha() {
        repository.insertEntity(newLevel(levelId = "lv-1", sha = "sha-1"))
        repository.insertEntity(newLevel(levelId = "lv-2", sha = "sha-2"))
        repository.insertEntity(newLevel(levelId = "lv-3", sha = "sha-3"))

        val projections = repository.findAllShaBy()
        assertEquals(setOf("sha-1", "sha-2", "sha-3"), projections.map { it.sha }.toSet())
        assertTrue(projections.all { it.sha.isNotEmpty() }, "sha 非空列，投影值应非空")
    }

    @Test
    fun findAllShaByEmptyTable() {
        assertTrue(repository.findAllShaBy().isEmpty())
    }

    // ------------------------------------------------------------------ findAllByCatOne

    @Test
    fun findAllByCatOnePagination() {
        repeat(3) { repository.insertEntity(newLevel(levelId = "act-$it", catOne = "活动")) }
        repository.insertEntity(newLevel(levelId = "main-1", catOne = "主线"))
        repository.insertEntity(newLevel(levelId = "null-cat", catOne = null))

        val page0 = repository.findAllByCatOne("活动", PageRequest.of(0, 2))
        assertEquals(2, page0.content.size)
        assertEquals(3L, page0.totalElements, "total 应为命中 cat_one 的总行数（不含 NULL 行）")
        assertEquals(0, page0.number)
        assertEquals(2, page0.totalPages)

        val page1 = repository.findAllByCatOne("活动", PageRequest.of(1, 2))
        assertEquals(1, page1.content.size)
        assertEquals(3L, page1.totalElements)
    }

    @Test
    fun findAllByCatOneNoHitAndNullCatOneNotMatched() {
        repository.insertEntity(newLevel(levelId = "null-cat", catOne = null))
        repository.insertEntity(newLevel(levelId = "main-1", catOne = "主线"))

        val page = repository.findAllByCatOne("活动", PageRequest.of(0, 2))
        assertTrue(page.content.isEmpty())
        assertEquals(0L, page.totalElements)
        // cat_one = NULL 的行不被 "主线" 之外的值命中（上句已隐含）；空表查询
    }

    @Test
    fun findAllByCatOneOffsetBeyondReturnsEmpty() {
        repeat(3) { repository.insertEntity(newLevel(levelId = "act-$it", catOne = "活动")) }

        val page = repository.findAllByCatOne("活动", PageRequest.of(5, 2))
        assertTrue(page.content.isEmpty())
        assertEquals(3L, page.totalElements, "越界页 content 为空但 total 不变")
    }

    @Test
    fun findAllByCatOnePageSizeCoversAll() {
        repeat(3) { repository.insertEntity(newLevel(levelId = "act-$it", catOne = "活动")) }

        val page = repository.findAllByCatOne("活动", PageRequest.of(0, 10))
        assertEquals(3, page.content.size)
        assertEquals(3L, page.totalElements)
        assertEquals(1, page.totalPages)
    }

    // ------------------------------------------------------------------ saveAll

    @Test
    fun saveAllMixedNewAndExisting() {
        repository.insertEntity(newLevel(levelId = "lv-old", name = "旧名"))
        val attached = repository.findById(1L)!!
        attached.name = "新名"

        repository.saveAll(
            listOf(
                attached,
                newLevel(levelId = "lv-new-1", name = "新一"),
                newLevel(levelId = "lv-new-2", name = "新二"),
            ),
        )

        assertEquals(3, repository.count(), "旧实体更新、新实体插入，共 3 行")
        assertEquals("新名", repository.findByLevelId("lv-old")!!.name)
        assertEquals("新一", repository.findByLevelId("lv-new-1")!!.name)
        assertEquals("新二", repository.findByLevelId("lv-new-2")!!.name)
    }

    @Test
    fun saveAllEmptyListNoOp() {
        repository.saveAll(emptyList())
        assertEquals(0, repository.count())
    }

    @Test
    fun saveAllNewEntitiesBackfillIds() {
        val entities = listOf(
            newLevel(levelId = "lv-1"),
            newLevel(levelId = "lv-2"),
            newLevel(levelId = "lv-3"),
        )
        repository.saveAll(entities)

        assertEquals(listOf(1L, 2L, 3L), entities.map { it.id }, "全部新实体 id 应依次回填")
        assertEquals(3, repository.count())
    }

    // ------------------------------------------------------------------ count（继承）

    @Test
    fun countEmptyAndWithRows() {
        assertEquals(0L, repository.count())
        repository.insertEntity(newLevel(levelId = "lv-1"))
        repository.insertEntity(newLevel(levelId = "lv-2"))
        assertEquals(2L, repository.count())
    }

    // ------------------------------------------------------------------ 无唯一约束基线

    @Test
    fun insertDuplicateLevelIdAllowed() {
        // 基线记录：ark_level 无唯一约束（V1__init.sql 中 level_id/stage_id/name 索引均非 unique），
        // "唯一约束冲突" 场景对本模块不适用；重复 level_id 可共存。
        repository.insertEntity(newLevel(levelId = "dup", name = "第一行"))
        repository.insertEntity(newLevel(levelId = "dup", name = "第二行"))

        assertEquals(2, repository.count())
        val fuzzy = repository.findByLevelIdFuzzy("dup")
        assertEquals(2, fuzzy.size)
        assertEquals(setOf("第一行", "第二行"), fuzzy.map { it.name }.toSet())
        // findByLevelId 只返回其一（LIMIT 1）
        assertNotNull(repository.findByLevelId("dup"))
    }
}
