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
 * findAllShaBy / findAllByCatOne / saveAll / count（继承）/
 * countBlankCatTwoByCatOne / findAllBlankCatTwoByCatOne / updateCatTwoByIds /
 * findAllOrdered / findAllUpdatedSince / countNullUpdatedAt / findAllNullUpdatedAt / updateUpdatedAtByIds。
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
        updatedAt: LocalDateTime? = LocalDateTime.now(),
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
        updatedAt = updatedAt,
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

    // ------------------------------------------------------------------ 空名回填支持

    @Test
    fun countBlankCatTwoByCatOneCountsNullAndEmptyOnly() {
        repository.insertEntity(newLevel(levelId = "act-1", catOne = "活动关卡", catTwo = null))
        repository.insertEntity(newLevel(levelId = "act-2", catOne = "活动关卡", catTwo = ""))
        repository.insertEntity(newLevel(levelId = "act-3", catOne = "活动关卡", catTwo = "登临意"))
        repository.insertEntity(newLevel(levelId = "main-1", catOne = "主题曲", catTwo = null))

        assertEquals(2L, repository.countBlankCatTwoByCatOne("活动关卡"), "NULL 与空串都计入，有值行不计入")
        assertEquals(1L, repository.countBlankCatTwoByCatOne("主题曲"), "其它分类不误召")
        assertEquals(0L, repository.countBlankCatTwoByCatOne("不存在的分类"))
        assertEquals(0L, repository.countBlankCatTwoByCatOne("活动"), "前缀不匹配")
    }

    @Test
    fun countBlankCatTwoByCatOneWithNoRowsAndWithNoBlanks() {
        assertEquals(0L, repository.countBlankCatTwoByCatOne("活动关卡"))

        repository.insertEntity(newLevel(levelId = "act-1", catOne = "活动关卡", catTwo = "登临意"))
        assertEquals(0L, repository.countBlankCatTwoByCatOne("活动关卡"))
    }

    @Test
    fun findAllBlankCatTwoByCatOneReturnsBlanksOfThatCatOneOnly() {
        val blankNull = repository.insertEntity(newLevel(levelId = "act-1", catOne = "活动关卡", catTwo = null))
        val blankEmpty = repository.insertEntity(newLevel(levelId = "act-2", catOne = "活动关卡", catTwo = ""))
        repository.insertEntity(newLevel(levelId = "act-3", catOne = "活动关卡", catTwo = "登临意"))
        repository.insertEntity(newLevel(levelId = "main-1", catOne = "主题曲", catTwo = null))

        val blanks = repository.findAllBlankCatTwoByCatOne("活动关卡")
        assertEquals(setOf(blankNull.id, blankEmpty.id), blanks.map { it.id }.toSet())
        assertEquals(setOf("act-1", "act-2"), blanks.map { it.levelId }.toSet())
    }

    @Test
    fun findAllBlankCatTwoByCatOneEmptyTable() {
        assertTrue(repository.findAllBlankCatTwoByCatOne("活动关卡").isEmpty())
    }

    @Test
    fun updateCatTwoByIdsUpdatesOnlyCatTwo() {
        // 回填与开放状态跑批可能并发（全列 upsert 会把 is_open/close_time 回写成旧值），
        // 这里锁死「定向更新只改 cat_two」这一竞态防护。
        val closeTime = LocalDateTime.of(2024, 5, 1, 12, 30, 0)
        val entity = repository.insertEntity(
            newLevel(
                levelId = "activities/act1dp/level_act1dp_01", stageId = "act1dp_01", sha = "sha-1",
                catOne = "活动关卡", catTwo = "", catThree = "DP-1", name = "登临意",
                width = 9, height = 6, isOpen = true, closeTime = closeTime,
            ),
        )
        val untouched = repository.insertEntity(newLevel(levelId = "act-2", catOne = "活动关卡", catTwo = ""))

        assertEquals(1, repository.updateCatTwoByIds(listOf(entity.id to "登临意")))
        assertEquals(0, repository.updateCatTwoByIds(listOf(999L to "登临意")), "不存在的行返回 0")

        val loaded = repository.findById(entity.id)!!
        assertEquals("登临意", loaded.catTwo)
        assertEquals("activities/act1dp/level_act1dp_01", loaded.levelId)
        assertEquals("act1dp_01", loaded.stageId)
        assertEquals("sha-1", loaded.sha)
        assertEquals("活动关卡", loaded.catOne)
        assertEquals("DP-1", loaded.catThree)
        assertEquals("登临意", loaded.name)
        assertEquals(9, loaded.width)
        assertEquals(6, loaded.height)
        assertEquals(true, loaded.isOpen)
        assertEquals(closeTime, loaded.closeTime)

        assertEquals("", repository.findById(untouched.id)!!.catTwo, "未被指定的行不改动")
    }

    @Test
    fun updateCatTwoByIdsSkipsRowThatAlreadyHasValue() {
        // 条件更新的核心：并发回填时不能用更旧快照解析出的名字覆盖已填好的值。
        // 回填只查询空值行，被写坏的行不会再进入视野，因此这种覆盖不会自愈，必须在写入时就挡住。
        val entity = repository.insertEntity(newLevel(levelId = "act-1", catOne = "活动关卡", catTwo = "登临意"))

        assertEquals(0, repository.updateCatTwoByIds(listOf(entity.id to "旧快照解析出的名字")), "已有值不应被覆盖")
        assertEquals("登临意", repository.findById(entity.id)!!.catTwo)
    }

    @Test
    fun updateCatTwoByIdsCanWriteEmptyStringToBlankRow() {
        // 空串是合法取值（解析不出活动名时 parser 即写空串），不应把它当哨兵值排除；
        // 但同样只在目标行仍为空时生效（NULL 与空串都算空）
        val nullRow = repository.insertEntity(newLevel(levelId = "act-1", catOne = "活动关卡", catTwo = null))
        val emptyRow = repository.insertEntity(newLevel(levelId = "act-2", catOne = "活动关卡", catTwo = ""))

        assertEquals(1, repository.updateCatTwoByIds(listOf(nullRow.id to "")))
        assertEquals("", repository.findById(nullRow.id)!!.catTwo)
        assertEquals(1, repository.updateCatTwoByIds(listOf(emptyRow.id to "登临意")))
        assertEquals("登临意", repository.findById(emptyRow.id)!!.catTwo)
    }

    @Test
    fun updateCatTwoByIdsAffectedCountCountsOnlyWrittenRows() {
        // 批量更新的返回值用于区分 repaired / skipped：必须只统计真正写成功的行，
        // 差额即竞争失败（条件未满足的行：已有值或不存在的 id）
        val blank = repository.insertEntity(newLevel(levelId = "act-blank", catOne = "活动关卡", catTwo = ""))
        val blankNull = repository.insertEntity(newLevel(levelId = "act-null", catOne = "活动关卡", catTwo = null))
        val valued = repository.insertEntity(newLevel(levelId = "act-valued", catOne = "活动关卡", catTwo = "已有名"))

        val affected = repository.updateCatTwoByIds(
            listOf(
                blank.id to "甲",
                valued.id to "乙",
                9999L to "丙",
                blankNull.id to "丁",
            ),
        )

        assertEquals(2, affected, "只有两个空值行被写入；有值行与不存在的行都不计")
        assertEquals("甲", repository.findById(blank.id)!!.catTwo)
        assertEquals("丁", repository.findById(blankNull.id)!!.catTwo)
        assertEquals("已有名", repository.findById(valued.id)!!.catTwo)
    }

    @Test
    fun updateCatTwoByIdsEmptyListNoOp() {
        repository.insertEntity(newLevel(levelId = "act-1", catOne = "活动关卡", catTwo = ""))

        assertEquals(0, repository.updateCatTwoByIds(emptyList()))
        assertEquals("", repository.findById(1L)!!.catTwo)
    }

    // ------------------------------------------------------------------ updated_at

    @Test
    fun insertEntityWritesUpdatedAtFromEntityDefault() {
        // 不显式传 updatedAt：新行走实体构造时刻（lite 窗口依赖它，缺失会让新行永远进不了 lite）
        val before = LocalDateTime.now().minusSeconds(1)
        val entity = repository.insertEntity(ArkLevelEntity(levelId = "act-1", stageId = "act-1", sha = "sha-1"))

        val loaded = repository.findById(entity.id)!!
        assertNotNull(loaded.updatedAt, "新行的 updated_at 不应为 NULL")
        assertTrue(loaded.updatedAt!!.isAfter(before), "应取构造时刻，实测 ${loaded.updatedAt}")
    }

    @Test
    fun insertEntityPersistsExplicitUpdatedAt() {
        val ts = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 678_000_000)
        val entity = repository.insertEntity(newLevel(levelId = "act-1", updatedAt = ts))

        assertEquals(ts, repository.findById(entity.id)!!.updatedAt)
    }

    @Test
    fun insertEntityNullUpdatedAtRoundTripsAsNull() {
        // 存量行（迁移后未回填）的形态：NULL 必须能读回，否则 mapTo 会在非空属性上炸掉
        val entity = repository.insertEntity(newLevel(levelId = "act-1", updatedAt = null))

        assertNull(repository.findById(entity.id)!!.updatedAt)
    }

    @Test
    fun upsertDoesNotRefreshUpdatedAt() {
        // 锁死 V2 方案的核心约束：开放状态跑批走 saveAll 全列 upsert，SET 列表里若带上 updated_at，
        // 每轮都会把整批活动关卡续期，lite 从 2.2K 膨胀到 49.9K。
        // 这里连调用方显式改了值也一并锁住——防护在 SQL 层，不在调用方自觉。
        val original = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0)
        val inserted = repository.insertEntity(newLevel(levelId = "act-1", isOpen = true, updatedAt = original))

        val attached = repository.findById(inserted.id)!!
        attached.isOpen = false
        attached.updatedAt = LocalDateTime.now() // 即使调用方给了一个新值
        repository.saveAll(listOf(attached))

        assertEquals(original, repository.findById(inserted.id)!!.updatedAt, "upsert 不得改写 updated_at")
        assertEquals(false, repository.findById(inserted.id)!!.isOpen, "其余列照旧全列覆盖")
    }

    @Test
    fun upsertHandlesNullUpdatedAtWithoutFailing() {
        // 生产上必然出现的中间态：迁移后、回填完成前，开放状态跑批（updateLevelsOfTypeInBatch）
        // 会把 updated_at 仍是 NULL 的行整页 upsert 回来。这里锁死该路径不报错，且 NULL 不被写成
        // 某个伪造值——否则回填的「只处理 NULL 行」判据会失效，这些行永远进不了 lite 窗口。
        val entity = repository.insertEntity(newLevel(levelId = "act-1", catOne = "活动关卡", updatedAt = null))
        assertNull(repository.findById(entity.id)!!.updatedAt)

        val page = repository.findById(entity.id)!!
        page.isOpen = true
        repository.saveAll(listOf(page))

        val loaded = repository.findById(entity.id)!!
        assertEquals(true, loaded.isOpen, "其余列应被更新")
        assertNull(loaded.updatedAt, "NULL 必须保持 NULL，等待回填")
        assertEquals(1L, repository.countNullUpdatedAt(), "该行仍应被回填门禁统计到")
    }

    @Test
    fun findAllOrderedIsOrderedByStageIdThenId() {
        // 版本摘要按该顺序拼接，顺序不确定会让同一份数据算出两个版本号
        repository.insertEntity(newLevel(levelId = "b", stageId = "st-2"))
        repository.insertEntity(newLevel(levelId = "a", stageId = "st-1"))
        repository.insertEntity(newLevel(levelId = "c", stageId = "st-2"))

        val rows = repository.findAllOrdered()
        assertEquals(listOf("st-1", "st-2", "st-2"), rows.map { it.stageId })
        assertEquals(listOf(2L, 1L, 3L), rows.map { it.id }, "stage_id 相同时按 id 升序（1 与 3 是 st-2 的两行）")
    }

    @Test
    fun findAllUpdatedSinceFiltersByCatOneAndWindow() {
        val cutoff = LocalDateTime.of(2026, 6, 25, 0, 0, 0)
        val inWindow = repository.insertEntity(
            newLevel(levelId = "act-new", catOne = "活动关卡", updatedAt = cutoff.plusDays(1)),
        )
        repository.insertEntity(newLevel(levelId = "act-old", catOne = "活动关卡", updatedAt = cutoff.minusDays(1)))
        repository.insertEntity(newLevel(levelId = "main-new", catOne = "主题曲", updatedAt = cutoff.plusDays(1)))
        repository.insertEntity(newLevel(levelId = "act-null", catOne = "活动关卡", updatedAt = null))
        val onBoundary = repository.insertEntity(newLevel(levelId = "act-edge", catOne = "活动关卡", updatedAt = cutoff))

        val rows = repository.findAllUpdatedSince("活动关卡", cutoff)
        assertEquals(setOf(inWindow.id, onBoundary.id), rows.map { it.id }.toSet(), "含边界值，不含其它分类与 NULL")
        assertEquals(listOf(inWindow.id, onBoundary.id), rows.map { it.id }, "按 stage_id, id 排序")
    }

    @Test
    fun countNullUpdatedAtAndFindAllNullUpdatedAt() {
        assertEquals(0L, repository.countNullUpdatedAt())
        assertTrue(repository.findAllNullUpdatedAt().isEmpty())

        val blank = repository.insertEntity(newLevel(levelId = "act-1", updatedAt = null))
        val blankNull = repository.insertEntity(newLevel(levelId = "act-2", updatedAt = null))
        repository.insertEntity(newLevel(levelId = "act-3", updatedAt = LocalDateTime.now()))

        assertEquals(2L, repository.countNullUpdatedAt())
        assertEquals(listOf(blank.id, blankNull.id), repository.findAllNullUpdatedAt().map { it.id })
    }

    @Test
    fun updateUpdatedAtByIdsOnlyFillsNullRows() {
        // 并发防护：轮询期间新同步进来的行已由 INSERT 写入真实时刻，回填不得覆盖它
        val ts = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0)
        val blank = repository.insertEntity(newLevel(levelId = "act-1", updatedAt = null))
        val filled = repository.insertEntity(newLevel(levelId = "act-2", updatedAt = ts))

        val affected = repository.updateUpdatedAtByIds(
            listOf(
                blank.id to ts,
                9999L to ts,
                filled.id to LocalDateTime.now(),
            ),
        )

        assertEquals(1, affected, "只有 NULL 行被写入；已填行与不存在的行都不计")
        assertEquals(ts, repository.findById(blank.id)!!.updatedAt)
        assertEquals(ts, repository.findById(filled.id)!!.updatedAt, "已有值不被覆盖")
    }

    @Test
    fun updateUpdatedAtByIdsEmptyListNoOp() {
        val blank = repository.insertEntity(newLevel(levelId = "act-1", updatedAt = null))

        assertEquals(0, repository.updateUpdatedAtByIds(emptyList()))
        assertNull(repository.findById(blank.id)!!.updatedAt)
    }
}
