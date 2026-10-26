package plus.maa.backend.service.recommendation

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.service.model.CopilotType
import java.time.LocalDateTime
import kotlin.random.Random

class RecommendationEngineTest {
    private val now = LocalDateTime.of(2026, 7, 10, 12, 0)
    private val operator = RecommendationOperator("char_test", "测试干员", "Caster", 6, listOf(0, 1, 2))
    private val other = RecommendationOperator("char_other", "备选干员", "Caster", 6, listOf(0, 1, 2))
    private val engine = RecommendationEngine(listOf(operator, other))
    private val levels = (1..4).map {
        ArkLevelEntity(stageId = "s$it", levelId = "main/s$it", catOne = "主题曲", catTwo = "章节", catThree = "1-$it")
    }
    private val history = RecommendationQuery(days = 0, scope = RecommendationScope.HISTORY, minOperationCount = 1)

    private fun row(
        id: Long,
        stage: String = "s1",
        age: Long = 10,
        likes: Long = 90,
        dislikes: Long = 10,
        views: Long = 100,
        level: Int = 60,
        skillLevel: Int = 7,
        module: Int = 1,
        name: String = operator.name,
        extra: String = "",
        actions: String = "[]",
        skill: Int = 3,
        requirements: String? = null,
        difficulty: Int = 0,
    ): RecommendationInput {
        val requirementsJson = requirements ?: """{
            "elite":2,"level":$level,"skill_level":$skillLevel,"module":$module$extra
        }"""
        val content = """{
            "stage_name":"$stage","difficulty":$difficulty,
            "opers":[{"name":"$name","skill":$skill,"requirements":$requirementsJson}],
            "actions":$actions,"doc":{"title":"作业$id"}
        }"""
        return RecommendationInput(
            CopilotEntity(
                copilotId = id, stageName = stage, content = content, title = "作业$id",
                firstUploadTime = now.minusDays(
                    age,
                ),
                uploadTime = now, likeCount = likes, dislikeCount = dislikes, views = views,
            ),
        )
    }
    private fun result(rows: List<RecommendationInput>, query: RecommendationQuery = history) = engine.calculate(rows, levels, query, now)

    @Test
    fun `compact persisted evidence preserves calculations while dropping raw documents and duplicate copies`() {
        val rows = (1L..100).map { id ->
            row(id).let { input ->
                input.copy(entity = input.entity.copy(content = input.entity.content.replace("作业$id", "作业$id" + "说明".repeat(2000))))
            }
        } + listOf(row(101, stage = "s2", level = 90), row(102).let { it.copy(entity = it.entity.copy(content = "{")) })
        val prepared = engine.prepare(rows, levels)
        val encoded = Json.encodeToString(prepared)
        val restored = Json.decodeFromString<RecommendationEvidence>(encoded)
        assertEquals(prepared, restored)
        assertEquals(2, restored.families.size)
        assertEquals(100, restored.families.first().operationCount)
        assertEquals(1, restored.families.first().variants.size)
        assertEquals(1, restored.operators.size)
        assertEquals(2, restored.stages.size)
        assertTrue(encoded.length < rows.sumOf { it.entity.content.length } / 20)
        assertTrue(listOf("content", "skeleton", "fingerprint", "uploadTime", "uploaderId").none { encoded.contains("\"$it\"") })
        RecommendationScope.entries.forEach { scope ->
            listOf(0, 7, 180).forEach { days ->
                listOf(0.6, 0.8, 0.9).forEach { coverage ->
                    val query = history.copy(scope = scope, days = days, coverage = coverage)
                    assertEquals(engine.calculate(prepared, query, now), RecommendationEngine().calculate(restored, query, now))
                }
            }
        }
    }

    @Test
    fun `streamed pages are parsed before advancing and source entities are not retained`() {
        val first = row(1, age = 1000)
        val copy = row(2, age = 10)
        val expected = engine.prepare(listOf(first, copy), levels)
        val streamed = sequence {
            yield(first)
            first.entity.content = "{"
            first.entity.title = "changed after parsing"
            first.entity.likeCount = 0
            first.entity.firstUploadTime = now
            yield(copy)
            copy.entity.content = "{"
            copy.entity.views = 0
        }
        val actual = engine.prepare(streamed, levels)
        assertEquals(expected, actual)
        assertEquals(1, actual.families.size)
        assertEquals(2, actual.families.single().operationCount)
        assertEquals(now.minusDays(1000), actual.families.single().firstPublishedAt)
        assertEquals(1L, actual.families.single().variants.single().id)
    }

    @Test
    fun `closed-only category yields data when closed activities are included and nothing otherwise`() {
        val closed = ArkLevelEntity(
            stageId = "crisis",
            levelId = "crisis/c1",
            catOne = "危机合约",
            catTwo = "测试",
            catThree = "C-1",
            isOpen = false,
        )
        val prepared = engine.prepare(listOf(row(1, stage = "crisis")), levels + closed)
        assertEquals(listOf("危机合约"), prepared.stages.mapNotNull { it.category })
        val closedQuery = history.copy(category = "危机合约")
        assertTrue(engine.calculate(prepared, closedQuery, now).recommendations.isEmpty())
        val opened = engine.calculate(prepared, closedQuery.copy(includeClosed = true), now)
        assertEquals(1, opened.operationCount)
        assertEquals(1, opened.recommendations.size)
        assertEquals(listOf("危机合约"), opened.stages.mapNotNull { it.category })
    }

    @Test
    fun `stage aliases resolve to one catalog stage and only catalog metadata becomes a filter`() {
        val stage = levels.first().copy(name = "关卡名称")
        val aliases = listOf(stage.stageId!!, stage.levelId!!, stage.catThree!!, stage.name!!, " S1 ")
        val rows = aliases.mapIndexed { index, alias -> row(index.toLong(), stage = alias) } +
            row(10).let { it.copy(entity = it.entity.copy(stageName = "过时的关卡名")) }
        val result = engine.calculate(rows, listOf(levels[1].copy(name = "s1"), stage), history, now)
        assertEquals(rows.size, result.operationCount)
        assertEquals(1, result.familyCount)
        assertEquals(0, result.invalidCount)
        assertEquals(listOf("主题曲"), result.categories)
        assertEquals(listOf("章节"), result.activities)
        assertEquals(listOf(RecommendationStage("s1", "1-1 关卡名称", "主题曲", "章节")), result.stages)
        assertEquals("s1", result.recommendations.single().sources.single().stageId)
    }

    @Test
    fun `stage dictionary preserves level aliases and metadata while counting shared stage ids once`() {
        val main = levels.first()
        val activity = main.copy(levelId = "activities/alias", catOne = "活动", catTwo = "活动名", isOpen = false)
        val rows = listOf(
            row(1, stage = main.levelId!!),
            row(2, stage = activity.levelId!!, actions = """[{"type":"Skill","name":"测试干员"}]"""),
        )
        val prepared = engine.prepare(rows, listOf(main, activity))
        val restored = Json.decodeFromString<RecommendationEvidence>(Json.encodeToString(prepared))
        assertEquals(2, restored.stages.size)
        val closed = engine.calculate(restored, history, now)
        assertEquals(1, closed.operationCount)
        assertEquals(listOf("主题曲", "活动"), closed.categories)
        val included = engine.calculate(restored, history.copy(includeClosed = true), now)
        assertEquals(2, included.operationCount)
        assertEquals(1, included.recommendations.single().stageCount)
        assertEquals(1, included.stages.size)
        assertEquals(main.catOne, included.stages.single().category)
        val focused = engine.calculate(restored, history.copy(stageId = activity.levelId, includeClosed = true), now)
        assertEquals(1, focused.operationCount)
        assertEquals(2L, focused.recommendations.single().sources.single().id)
    }

    @Test
    fun `unmatched stages and catalog rows without stage ids cannot become recommendations or filters`() {
        val rows = listOf(row(1), row(2, stage = "不存在的关卡"), row(3, stage = "空ID关卡"), row(4, stage = "空白ID关卡"))
        val catalog = levels + listOf(
            levels.first().copy(stageId = null, levelId = "main/missing", catThree = "空ID关卡", catTwo = "错误活动"),
            levels.first().copy(stageId = " ", levelId = "main/blank", name = "空白ID关卡", catTwo = "错误活动"),
        )
        val result = engine.calculate(rows, catalog, history, now)
        assertEquals(1, result.operationCount)
        assertEquals(3, result.invalidCount)
        assertEquals(listOf("章节"), result.activities)
        assertEquals(listOf("s1"), result.stages.map { it.id })
        assertEquals(result(listOf(row(1))).recommendations, result.recommendations)
    }

    @Test
    fun `ambiguous aliases are rejected while explicit ids and difficulty variants stay distinct`() {
        val stage = levels.first().copy(name = "重名关卡")
        val hard = stage.copy(stageId = "s1#f#", levelId = "hard/s1")
        val boss = stage.copy(stageId = "act1bossrush_01", levelId = "activities/boss", catThree = "B-1", name = "连战")
        val bossHard = boss.copy(stageId = "act1bossrush_tm01", levelId = "activities/boss-hard")
        val catalog = listOf(hard, stage, bossHard, boss, levels[1].copy(name = "重名关卡"))
        val rows = listOf(
            row(1, stage = "重名关卡"),
            row(2, stage = "1-1"),
            row(3, stage = "s1#f#"),
            row(4, stage = "B-1"),
            row(5, stage = "act1bossrush_tm01"),
        )
        val result = engine.calculate(rows, catalog, history, now)
        assertEquals(1, result.invalidCount)
        assertEquals(4, result.operationCount)
        assertEquals(setOf("s1", "s1#f#", "act1bossrush_01", "act1bossrush_tm01"), result.stages.map { it.id }.toSet())
        assertEquals(1, engine.calculate(rows, catalog, history.copy(stageId = "s1"), now).operationCount)
        assertEquals(1, engine.calculate(rows, catalog, history.copy(stageId = "act1bossrush_01"), now).operationCount)
        assertEquals(result, engine.calculate(rows, catalog.reversed(), history, now))
    }

    @Test
    fun `filter thresholds count operations per activity and stage and omit internal activity codes`() {
        val catalog = levels.mapIndexed { index, stage ->
            stage.copy(
                catTwo = listOf("活动甲", "活动乙", "活动乙", "level_crisis_v2_03-01")[index],
            )
        } + levels.first().copy(stageId = "s5", levelId = "rune/s5", catTwo = "level_rune_02-02")
        val rows = listOf(5, 2, 3, 5, 5).flatMapIndexed { index, count ->
            (1..count).map { row(index * 10L + it, stage = "s${index + 1}") }
        } + row(100).let { it.copy(entity = it.entity.copy(content = "{")) }
        val query = history.copy(minOperationCount = 5)
        val result = engine.calculate(rows, catalog, query, now)
        assertEquals(listOf("活动乙", "活动甲"), result.activities)
        assertEquals(setOf("s1", "s4", "s5"), result.stages.map { it.id }.toSet())
        assertEquals(20, result.operationCount)
        assertEquals(1, result.invalidCount)
        val relaxed = engine.calculate(rows, catalog, history, now)
        assertEquals(result.activities, relaxed.activities)
        assertEquals(catalog.map { it.stageId }.toSet(), relaxed.stages.map { it.id }.toSet())
        assertEquals(result.recommendations, relaxed.recommendations)
        val stricter = engine.calculate(rows, catalog, query.copy(minOperationCount = 6), now)
        assertTrue(stricter.activities.isEmpty())
        assertTrue(stricter.stages.isEmpty())
        assertEquals(result.recommendations, stricter.recommendations)
        val focused = engine.calculate(rows, catalog, query.copy(activity = "活动乙", stageId = "s2"), now)
        assertEquals(result.activities, focused.activities)
        assertTrue(focused.stages.isEmpty())
        assertEquals(2, focused.operationCount)
        assertEquals("s2", focused.recommendations.single().sources.single().stageId)
        val category = engine.calculate(rows, catalog, query.copy(category = "危机合约"), now)
        assertTrue(category.activities.isEmpty())
        assertTrue(category.stages.isEmpty())
    }

    @Test
    fun `filter options respect time windows open status scope and parent category`() {
        val catalog = levels.map { it.copy(levelId = "activities/${it.stageId}", catOne = "活动关卡", catTwo = it.stageId) }
            .map { if (it.stageId == "s2") it.copy(isOpen = false) else it } +
            levels.first().copy(stageId = "main", catTwo = "主线章节")
        val rows = listOf("s1" to 10L, "s2" to 10L, "s3" to 1000L, "main" to 1000L).flatMapIndexed { index, (stage, age) ->
            (1..5).map { row(index * 10L + it, stage = stage, age = age) }
        }
        val query = RecommendationQuery(scope = RecommendationScope.HISTORY, days = 90)
        val recent = engine.calculate(rows, catalog, query, now)
        assertEquals(listOf("s1"), recent.activities)
        assertEquals(listOf("s1"), recent.stages.map { it.id })
        val closed = engine.calculate(rows, catalog, query.copy(includeClosed = true), now)
        assertEquals(listOf("s1", "s2"), closed.activities)
        assertEquals(setOf("s1", "s2"), closed.stages.map { it.id }.toSet())
        val current = engine.calculate(rows, catalog, RecommendationQuery(), now)
        assertEquals(setOf("s1", "main"), current.stages.map { it.id }.toSet())
        val all = engine.calculate(rows, catalog, query.copy(days = 0, includeClosed = true), now)
        assertEquals(setOf("s1", "s2", "s3", "main"), all.stages.map { it.id }.toSet())
        val category = engine.calculate(rows, catalog, query.copy(days = 0, category = "主题曲"), now)
        assertEquals(listOf("主线章节"), category.activities)
        assertEquals(listOf("main"), category.stages.map { it.id })
        val permanent = engine.calculate(rows, catalog, query.copy(scope = RecommendationScope.PERMANENT), now)
        assertEquals(listOf("主线章节"), permanent.activities)
        assertEquals(listOf("main"), permanent.stages.map { it.id })
    }

    @Test
    fun `copies never multiply ratings views or independent support`() {
        val original = row(1)
        val one = result(listOf(original))
        val copies = result(listOf(original) + (2L..100L).map { row(it, views = 1_000_000) })
        assertEquals(1, copies.familyCount)
        assertEquals(one.recommendations.single().score, copies.recommendations.single().score, 1e-9)
        assertEquals(1, copies.recommendations.single().familyCount)
        assertEquals(one.recommendations.single().sources.single(), copies.recommendations.single().sources.single())
    }

    @Test
    fun `timing and presentation edits remain one family`() {
        val first = row(1, actions = """[{"type":"Deploy","name":"测试干员","location":[1,2],"direction":"Up","pre_delay":100}]""")
        val second =
            row(2, actions = """[{"direction":"Up","location":[1,2],"name":"测试干员","type":"Deploy","pre_delay":500,"doc":"改了说明"}]""")
        val one = result(listOf(first)).recommendations.single()
        val variants = result(listOf(first, second))
        assertEquals(1, variants.familyCount)
        assertEquals(one.score, variants.recommendations.single().score, 1e-9)
        assertEquals(one.sources.single(), variants.recommendations.single().sources.single())
    }

    @Test
    fun `localized actions and directions do not add votes or independent support`() {
        val types = listOf(
            "Deploy" to "部署", "Skill" to "技能", "Retreat" to "撤退", "SpeedUp" to "二倍速",
            "BulletTime" to "子弹时间", "SkillUsage" to "技能用法", "SkillDaemon" to "摆完挂机",
            "MoveCamera" to "移动相机", "Click" to "点击", "Swipe" to "滑动", "SetUnitLocation" to "设置单位坐标",
        )
        val directions = listOf("Up" to "上", "Down" to "下", "Left" to "左", "Right" to "右", "None" to "无")
        types.forEach { (english, chinese) ->
            directions.forEach { (direction, localized) ->
                val original = row(
                    1,
                    actions = """[{"type":"$english","name":"测试干员","location":[1,2],"direction":"$direction"}]""",
                )
                val copy = row(
                    2,
                    likes = 1,
                    dislikes = 0,
                    actions = """[
                        {"type":"打印","doc":"说明"},
                        {"type":"$chinese","name":"测试干员","location":[1,2],"direction":"$localized"},
                        {"type":"Output","doc":"more notes"}
                    ]""",
                )
                val one = result(listOf(original)).recommendations.single()
                val copies = result(listOf(original, copy))
                assertEquals(1, copies.familyCount, "$english/$direction")
                assertEquals(one.score, copies.recommendations.single().score, 1e-9)
                val anotherStage = row(3, stage = "s2")
                assertTrue(result(listOf(original, copy, anotherStage), RecommendationQuery()).recommendations.isEmpty())
            }
        }
        val implicit = row(1, actions = """[{"name":"测试干员","location":[1,2]}]""")
        val explicit = row(2, actions = """[{"type":"Deploy","name":"测试干员","location":[1,2],"direction":"无"}]""")
        assertEquals(
            result(listOf(implicit)).recommendations.single().score,
            result(listOf(implicit, explicit)).recommendations.single().score,
            1e-9,
        )
    }

    @Test
    fun `timing corrections retain their own quality without adding an independent strategy`() {
        val original = row(1, likes = 10, dislikes = 90, actions = """[{"type":"Deploy","name":"测试干员","location":[1,2],"pre_delay":100}]""")
        val corrected = row(2, actions = """[{"type":"Deploy","name":"测试干员","location":[1,2],"pre_delay":500}]""")
        val result = result(listOf(original, corrected))
        assertEquals(1, result.familyCount)
        assertEquals(2L, result.recommendations.single().sources.single().id)
    }

    @Test
    fun `small roster changes stay correlated but cannot transfer original quality to a replacement`() {
        val catalog = (1..8).map { operator.copy(id = "char_$it", name = "干员$it") }
        val originalNames = (1..7).map { "干员$it" }
        val modifiedNames = (1..6).map { "干员$it" } + "干员8"
        fun input(id: Long, names: List<String>, likes: Long, dislikes: Long): RecommendationInput {
            val opers = names.joinToString(",") { """{"name":"$it","skill":3}""" }
            val actions = names.mapIndexed { index, name ->
                """{"type":"Deploy","name":"$name","location":[$index,0],"direction":"Up"}"""
            }.joinToString(",")
            return row(id, likes = likes, dislikes = dislikes).let {
                it.copy(entity = it.entity.copy(content = """{"stage_name":"s1","opers":[$opers],"actions":[$actions]}"""))
            }
        }
        val result = RecommendationEngine(catalog).calculate(
            listOf(input(1, originalNames, 90, 10), input(2, modifiedNames, 1, 99)),
            levels,
            history,
            now,
        )
        assertEquals(1, result.familyCount)
        assertTrue(result.recommendations.none { it.operator.id == "char_8" })
    }

    @Test
    fun `old v2 module indexes use the corresponding operator module order`() {
        val catalog = operator.copy(modules = listOf(0, 2, 1))
        val input = row(1).let { it.copy(entity = it.entity.copy(content = it.entity.content.replaceFirst("{", "{\"version\":2,"))) }
        val target = RecommendationEngine(listOf(catalog)).calculate(listOf(input), levels, history, now)
            .recommendations.single().branches.single().target
        assertEquals(2, target.module)
        assertNull(target.moduleLevel)
    }

    @Test
    fun `invalid modules are rejected while the default sentinel remains valid`() {
        listOf(false, true).forEach { v2 ->
            fun input(module: Int) = row(1, module = module).let {
                if (v2) it.copy(entity = it.entity.copy(content = it.entity.content.replaceFirst("{", "{\"version\":2,"))) else it
            }
            assertNull(result(listOf(input(-1))).recommendations.single().branches.single().target.module)
            assertEquals(0, result(listOf(input(0))).recommendations.single().branches.single().target.module)
            listOf(-2, 3, 99).forEach { module ->
                val invalid = result(listOf(input(module)))
                assertTrue(invalid.recommendations.isEmpty(), "module=$module, v2=$v2")
                assertEquals(1, invalid.invalidCount)
            }
        }
        val unmappedCatalog = operator.copy(modules = listOf(0, null, 1))
        val input = row(1).let { it.copy(entity = it.entity.copy(content = it.entity.content.replaceFirst("{", "{\"version\":2,"))) }
        val invalid = RecommendationEngine(listOf(unmappedCatalog)).calculate(listOf(input), levels, history, now)
        assertTrue(invalid.recommendations.isEmpty())
        assertEquals(1, invalid.invalidCount)
    }

    @Test
    fun `a fresh copy or a recent edit does not renew an old family`() {
        val old = row(1, age = 1000)
        val copy = row(2, age = 2)
        val query = RecommendationQuery(stageId = "s1")
        assertTrue(result(listOf(old, copy), query).recommendations.isEmpty())
        assertTrue(result(listOf(old), query).recommendations.isEmpty())
        assertEquals("HISTORICAL", result(listOf(old)).recommendations.single().status)
    }

    @Test
    fun `Wilson separates uncertainty from evidence of poor quality`() {
        assertTrue(RecommendationEngine.wilson(1, 0).first < RecommendationEngine.wilson(90, 10).first)
        assertEquals(0.0 to 1.0, RecommendationEngine.wilson(0, 0))
        assertTrue(RecommendationEngine.wilson(10, 90).second < 0.6)
        assertTrue(result(listOf(row(1, likes = 10, dislikes = 90, views = 1_000_000))).recommendations.isEmpty())
        assertTrue(result(listOf(row(1, likes = 0, dislikes = 0))).recommendations.isEmpty())
        assertEquals(
            "INSUFFICIENT",
            result(listOf(row(1, likes = 0, dislikes = 0)), history.copy(includeUncertain = true)).recommendations.single().status,
        )
    }

    @Test
    fun `young work is weighted below mature work with the same votes`() {
        val young = row(1).let { it.copy(entity = it.entity.copy(firstUploadTime = now.minusHours(1))) }
        assertTrue(result(listOf(young)).recommendations.single().score < result(listOf(row(1))).recommendations.single().score)
    }

    @Test
    fun `low traffic on another stage does not lose to absolute popularity`() {
        val rows = listOf(row(1, views = 1), row(2, stage = "s2", views = 1_000_000, name = other.name))
        val recommendations = result(rows).recommendations
        assertEquals(recommendations[0].score, recommendations[1].score, 1e-9)
    }

    @Test
    fun `permanent and temporary stages sharing a category keep separate budgets`() {
        val rows = listOf(row(1), row(2, stage = "s2", name = other.name))
        val mixedLevels = levels.map { if (it.stageId == "s2") it.copy(levelId = "activities/s2") else it }
        val mixed = engine.calculate(rows, mixedLevels, history, now)
        val baseline = result(listOf(row(1))).recommendations.single().score
        val recommendations = mixed.recommendations.associateBy { it.operator.id }
        assertEquals(baseline * 0.3, recommendations.getValue(operator.id).score, 1e-9)
        assertEquals(baseline * 0.7, recommendations.getValue(other.id).score, 1e-9)
        assertTrue(mixed.recommendations.all { it.branches.single().coverage == 1.0 })
    }

    @Test
    fun `default requires recent independent support while focused mode accepts a specialist`() {
        assertTrue(result(listOf(row(1)), RecommendationQuery()).recommendations.isEmpty())
        assertEquals("CURRENT", result(listOf(row(1)), RecommendationQuery(stageId = "s1")).recommendations.single().status)
        val rows = listOf(row(1), row(2, stage = "s2"), row(3, stage = "s3"))
        assertEquals("CURRENT", result(rows, RecommendationQuery()).recommendations.single().status)
    }

    @Test
    fun `difficulty flags on one catalog stage cannot satisfy general stage coverage`() {
        val aliases = listOf("s1", "main/s1", "1-1")
        val rows = listOf(0, 1, 3).mapIndexed { index, difficulty ->
            row(
                index + 1L,
                stage = aliases[index],
                difficulty = difficulty,
                actions = """[{"type":"Deploy","name":"测试干员","location":[$index,0]}]""",
            )
        }
        for (query in listOf(history, RecommendationQuery(includeUncertain = true))) {
            val recommendation = result(rows, query).recommendations.single()
            assertEquals(1, recommendation.stageCount)
            assertEquals(1, recommendation.recentStageCount)
            assertEquals(3, recommendation.familyCount)
            assertEquals(3, recommendation.recentFamilyCount)
            assertEquals(setOf("s1"), recommendation.sources.map { it.stageId }.toSet())
            assertEquals("LIMITED", recommendation.status)
        }
        assertTrue(result(rows, RecommendationQuery()).recommendations.isEmpty())
        for (query in listOf(RecommendationQuery(stageId = "s1"), RecommendationQuery(activity = "章节"))) {
            assertEquals("CURRENT", result(rows, query).recommendations.single().status)
        }
    }

    @Test
    fun `distinct normal and challenge catalog stages retain coverage and difficulty grouping`() {
        val stage = levels.first()
        val catalog = listOf(stage, stage.copy(stageId = "s1#f#", levelId = "hard/s1"))
        val rows = listOf(row(1, difficulty = 1), row(2, difficulty = 3), row(3, stage = "s1#f#", difficulty = 3))
        val recommendation = engine.calculate(rows, catalog, RecommendationQuery(), now).recommendations.single()
        assertEquals(2, recommendation.stageCount)
        assertEquals(2, recommendation.recentStageCount)
        assertEquals(3, recommendation.familyCount)
        assertEquals(3, recommendation.recentFamilyCount)
        assertEquals(setOf("s1", "s1#f#"), recommendation.sources.map { it.stageId }.toSet())
        assertEquals("CURRENT", recommendation.status)
    }

    @Test
    fun `independent low traffic stages can jointly provide reliable support`() {
        val rows = (1L..3L).map { row(it, stage = "s$it", likes = 2, dislikes = 0, views = 5) }
        assertEquals("CURRENT", result(rows, RecommendationQuery()).recommendations.single().status)
    }

    @Test
    fun `fresh positive feedback supports a still useful old strategy`() {
        val rows = (1L..3L).map { row(it, stage = "s$it", age = 1000).copy(latestPositiveAt = now.minusDays(1)) }
        assertEquals("CURRENT", result(rows, RecommendationQuery()).recommendations.single().status)
    }

    @Test
    fun `permanent catch up retains old evidence without treating weak feedback as recent support`() {
        val old = row(1, age = 1000, likes = 1, dislikes = 1).copy(latestPositiveAt = now.minusDays(1))
        val recentHistory = RecommendationQuery(scope = RecommendationScope.HISTORY, days = 90, includeUncertain = true)
        assertTrue(result(listOf(old), recentHistory).recommendations.isEmpty())
        val permanent = result(listOf(old), recentHistory.copy(scope = RecommendationScope.PERMANENT)).recommendations.single()
        assertEquals("INSUFFICIENT", permanent.status)
        assertEquals(0, permanent.recentFamilyCount)
        val reliable = result(listOf(row(2, age = 1000)), recentHistory.copy(scope = RecommendationScope.PERMANENT))
        assertEquals("HISTORICAL", reliable.recommendations.single().status)
        assertEquals(1, result(listOf(old), history).recommendations.size)
    }

    @Test
    fun `legacy original module does not require an equipment catalog entry`() {
        val robot = RecommendationOperator("robot", "小车", "Medic", 1)
        val input = row(1).let {
            it.copy(
                entity = it.entity.copy(
                    content = """{
            "version":2,"stage_name":"s1","opers":[{"name":"小车","requirements":{"elite":0,"level":30,"module":0}}]
        }""",
                ),
            )
        }
        val target = RecommendationEngine(listOf(robot)).calculate(listOf(input), levels, history, now)
            .recommendations.single().branches.single().target
        assertEquals(0, target.module)
        assertEquals(30, target.level)
    }

    @Test
    fun `Amiya third skill belongs only to the caster form`() {
        for (role in listOf("Warrior", "Medic")) {
            val amiya = RecommendationOperator("amiya_$role", "阿米娅", role, 5)
            val input = row(1, name = amiya.name, skill = 3, module = 0)
            assertTrue(RecommendationEngine(listOf(amiya)).calculate(listOf(input), levels, history, now).recommendations.isEmpty())
        }
    }

    @Test
    fun `cumulative coverage matches an exhaustive frontier with missing fields promotion resets and modules`() {
        val random = Random(713)
        fun covers(candidate: TrainingTarget, requirement: TrainingTarget): Boolean =
            (requirement.elite == null || (candidate.elite != null && candidate.elite >= requirement.elite)) &&
                (
                    requirement.level == null ||
                        (requirement.elite != null && candidate.elite != null && candidate.elite > requirement.elite) ||
                        (candidate.level != null && candidate.level >= requirement.level)
                    ) &&
                (requirement.skillLevel == null || (candidate.skillLevel != null && candidate.skillLevel >= requirement.skillLevel))
        repeat(60) { iteration ->
            val module = if (iteration % 3 == 0) 1 else null
            val targets = List(24) {
                val elite = listOf(null, 0, 1, 2).random(random)
                val cap = when (elite) {
                    0 -> 50
                    1 -> 80
                    else -> 90
                }
                TrainingTarget(
                    elite,
                    if (random.nextBoolean()) null else random.nextInt(1, cap + 1),
                    1,
                    if (random.nextBoolean()) null else random.nextInt(1, 11),
                    module,
                )
            }.distinct()
            val rows = targets.mapIndexed { index, target ->
                row(
                    index.toLong() + 1,
                    skill = 1,
                    requirements = """{
                    "elite":${target.elite},"level":${target.level},"skill_level":${target.skillLevel},"module":${target.module}
                }""",
                )
            }
            val requested = listOf(0.5, 0.6, 0.8, 1.0)[iteration % 4]
            val candidates = (targets.map { it.elite } + listOf(0, 1, 2)).distinct().flatMap { elite ->
                (targets.map { it.level } + 1 + if (module == 1) listOf(60) else emptyList()).distinct().flatMap { level ->
                    targets.map { it.skillLevel }.distinct().map { skill -> TrainingTarget(elite, level, 1, skill, module) }
                }
            }.filter { target ->
                val unlock = if (module == 1 || (target.skillLevel ?: 0) > 7) {
                    2
                } else if ((target.skillLevel ?: 0) > 4) {
                    1
                } else {
                    0
                }
                val cap = when (target.elite) {
                    0 -> 50
                    1 -> 80
                    else -> 90
                }
                (if (target.elite == null) unlock == 0 && target.level == null else target.elite >= unlock) &&
                    (target.level == null || target.level <= cap) && (module != 1 || (target.level ?: 0) >= 60)
            }
            val qualifying = candidates.filter { target ->
                targets.count { covers(target, it) }.toDouble() / targets.size + 1e-9 >=
                    requested
            }
            val expected = qualifying.filter { target -> qualifying.none { it != target && covers(target, it) && !covers(it, target) } }
            val branches = result(rows, history.copy(coverage = requested)).recommendations.single().branches
            assertEquals(expected.toSet(), branches.map { it.target }.toSet(), "iteration $iteration")
            branches.forEach { branch ->
                assertEquals(targets.count { covers(branch.target, it) }.toDouble() / targets.size, branch.coverage, 1e-9)
            }
        }
    }

    @Test
    fun `joint coverage joins requirements instead of averaging incompatible training levels`() {
        val rows = listOf(row(1, level = 60, skillLevel = 10), row(2, level = 90, skillLevel = 7))
        val target = result(rows).recommendations.single().branches.single()
        assertEquals(90, target.target.level)
        assertEquals(10, target.target.skillLevel)
        assertEquals(1.0, target.coverage, 1e-9)
    }

    @Test
    fun `zero training floors do not forbid skill or module unlocks`() {
        val noFloor = row(1, requirements = """{"elite":0,"level":0,"skill_level":0}""")
        val recommendation = result(listOf(noFloor)).recommendations.single()
        val branch = recommendation.branches.single()
        assertEquals(2, branch.target.elite)
        assertEquals(3, branch.target.skill)
        assertNull(branch.target.level)
        assertNull(branch.target.skillLevel)
        assertEquals(0.0, branch.levelDataRatio)
        assertEquals(0.0, branch.skillDataRatio)
        assertEquals(1.0, branch.coverage)

        val module = row(2, skill = 1, requirements = """{"elite":0,"level":30,"module":1}""")
        val moduleBranch = result(listOf(module)).recommendations.single().branches.single()
        assertEquals(2, moduleBranch.target.elite)
        assertEquals(60, moduleBranch.target.level)
        assertEquals(1, moduleBranch.target.module)
        assertEquals(1.0, moduleBranch.coverage)

        val lowModuleLevel = row(3, level = 20)
        assertEquals(60, result(listOf(lowModuleLevel)).recommendations.single().branches.single().target.level)
    }

    @Test
    fun `partial level requirements combine into a feasible full coverage target`() {
        val rows = listOf(
            row(1, module = 0),
            row(2, requirements = """{"level":90,"skill_level":7,"module":0}"""),
        )
        val branch = result(rows).recommendations.single().branches.single()
        assertEquals(2, branch.target.elite)
        assertEquals(90, branch.target.level)
        assertEquals(1.0, branch.coverage, 1e-9)
        assertEquals(0.5, branch.levelDataRatio, 1e-9)
    }

    @Test
    fun `promotion satisfies an earlier phase without carrying its level into the new phase`() {
        val rows = listOf(
            row(1, skill = 1, requirements = """{"elite":1,"level":80,"skill_level":7}"""),
            row(2, skill = 1, requirements = """{"elite":2,"level":1,"skill_level":7}"""),
        )
        val branch = result(rows).recommendations.single().branches.single()
        assertEquals(2, branch.target.elite)
        assertEquals(1, branch.target.level)
        assertEquals(1.0, branch.coverage, 1e-9)

        val mastery = row(3, skill = 1, requirements = """{"elite":0,"level":40,"skill_level":10}""")
        val target = result(listOf(mastery)).recommendations.single().branches.single().target
        assertEquals(2, target.elite)
        assertEquals(1, target.level)
        assertEquals(10, target.skillLevel)
    }

    @Test
    fun `rarity limits apply even when elite requirements are missing`() {
        val threeStar = RecommendationOperator("char_121_lava", "炎熔", "Caster", 3)
        val threeStarEngine = RecommendationEngine(listOf(threeStar))
        listOf("", "\"elite\":0,", "\"elite\":1,").forEach { elite ->
            (8..10).forEach { skillLevel ->
                val invalid = row(1, name = threeStar.name, skill = 1, requirements = """{$elite"skill_level":$skillLevel}""")
                val result = threeStarEngine.calculate(listOf(invalid), levels, history, now)
                assertTrue(result.recommendations.isEmpty())
                assertEquals(1, result.invalidCount)
            }
        }
        val valid = row(2, name = threeStar.name, skill = 1, requirements = """{"elite":0,"skill_level":7}""")
        val target = threeStarEngine.calculate(listOf(valid), levels, history, now).recommendations.single().branches.single().target
        assertEquals(1, target.elite)
        assertEquals(7, target.skillLevel)
    }

    @Test
    fun `inferred unlocks do not inflate source completeness`() {
        val input = row(1, requirements = """{"module":1}""")
        val branch = result(listOf(input)).recommendations.single().branches.single()
        assertEquals(2, branch.target.elite)
        assertEquals(60, branch.target.level)
        assertEquals(0.0, branch.levelDataRatio)
        assertEquals(0.0, branch.skillDataRatio)
        assertEquals(1.0, branch.moduleDataRatio)
    }

    @Test
    fun `different modules remain separate branches and module levels are ignored`() {
        val rows = listOf(row(1, module = 1, extra = ",\"module_level\":3"), row(2, module = 2))
        val branches = result(rows).recommendations.single().branches
        assertEquals(setOf(1, 2), branches.map { it.target.module }.toSet())
        assertTrue(branches.all { it.target.moduleLevel == null })
    }

    @Test
    fun `a poorly evaluated variant cannot borrow the original quality`() {
        val rows = listOf(row(1, module = 1), row(2, module = 2, likes = 1, dislikes = 99))
        assertEquals(listOf(1), result(rows).recommendations.single().branches.map { it.target.module })
    }

    @Test
    fun `group alternatives split one slot and can be excluded`() {
        val grouped = row(1).let { input ->
            input.copy(
                entity = input.entity.copy(
                    content = """{
                        "stage_name":"s1","groups":[{"name":"术师位","opers":[
                            {"name":"测试干员","skill":3},{"name":"备选干员","skill":3}
                        ]}]
                    }""",
                ),
            )
        }
        val recommendations = result(listOf(grouped)).recommendations
        val fixedScore = result(listOf(row(1))).recommendations.single().score
        assertEquals(2, recommendations.size)
        assertTrue(recommendations.all { it.sources.single().alternative })
        assertEquals(fixedScore / 2, recommendations.first().score, 1e-9)
        assertTrue(result(listOf(grouped), history.copy(includeAlternatives = false)).recommendations.isEmpty())
        assertTrue(recommendations.all { it.branches.single().target.level == null })
    }

    @Test
    fun `duplicate group entries count once and alternate skills retain their training shares`() {
        fun grouped(opers: String, secondGroup: String = "") = row(1).let {
            it.copy(
                entity = it.entity.copy(
                    content = """{
                "stage_name":"s1","groups":[{"name":"术师位","opers":[$opers]}$secondGroup]
            }""",
                ),
            )
        }
        val first = """{"name":"测试干员","skill":1}"""
        val second = """{"name":"测试干员","skill":2}"""
        val fixedScore = result(listOf(row(1, skill = 1, requirements = "{}"))).recommendations.single().score
        val duplicate = result(listOf(grouped("$first,$first"))).recommendations.single()
        assertEquals(fixedScore, duplicate.score, 1e-9)
        assertEquals(1, duplicate.branches.size)
        val choice = result(listOf(grouped("$first,$second"))).recommendations.single()
        assertEquals(fixedScore, choice.score, 1e-9)
        assertEquals(listOf(1, 2), choice.branches.map { it.target.skill })
        assertTrue(choice.branches.all { it.usageShare == 0.5 })
        assertEquals(1, choice.familyCount)
        val repeated = result(listOf(grouped("$first,$second", """,{"name":"重复位","opers":[$first,$second]}""")))
            .recommendations.single()
        assertEquals(fixedScore, repeated.score, 1e-9)
        assertTrue(repeated.branches.all { it.usageShare == 0.5 })
        assertTrue(result(listOf(grouped("$first,$second")), history.copy(includeAlternatives = false)).recommendations.isEmpty())
    }

    @Test
    fun `unparseable group alternatives lose only their own contribution`() {
        val fixedScore = result(listOf(row(1, requirements = "{}"))).recommendations.single().score
        val rejected = listOf(
            """{"name":"未知干员","skill":3}""",
            """{"name":"备选干员","skill":3,"requirements":{"level":100}}""",
            "null",
        )
        rejected.forEach { candidate ->
            val input = row(1).let {
                it.copy(
                    entity = it.entity.copy(
                        content = """{
                            "stage_name":"s1","groups":[{"name":"术师位","opers":[
                                {"name":"测试干员","skill":3},$candidate
                            ]}]
                        }""",
                    ),
                )
            }
            val recommendation = result(listOf(input)).recommendations.single()
            assertEquals(operator.id, recommendation.operator.id)
            assertEquals(fixedScore / 2, recommendation.score, 1e-9)
            assertTrue(recommendation.sources.single().alternative)
            assertTrue(result(listOf(input), history.copy(includeAlternatives = false)).recommendations.isEmpty())
        }
    }

    @Test
    fun `single member groups remain alternatives`() {
        val fixedScore = result(listOf(row(1, requirements = "{}"))).recommendations.single().score
        val input = row(1).let {
            it.copy(
                entity = it.entity.copy(
                    content = """{
                        "stage_name":"s1","groups":[{"name":"术师位","opers":[{"name":"测试干员","skill":3}]}]
                    }""",
                ),
            )
        }
        val recommendation = result(listOf(input)).recommendations.single()
        assertEquals(fixedScore, recommendation.score, 1e-9)
        assertTrue(recommendation.sources.single().alternative)
        assertTrue(result(listOf(input), history.copy(includeAlternatives = false)).recommendations.isEmpty())

        val fixed = row(2, requirements = "{}")
        val filtered = result(listOf(input, fixed), history.copy(includeAlternatives = false)).recommendations.single()
        assertEquals(fixedScore / 2, filtered.score, 1e-9)
        assertEquals(2L, filtered.sources.single().id)
        assertTrue(!filtered.sources.single().alternative)
    }

    @Test
    fun `fixed members keep their identity when also listed in a group`() {
        val fixedScore = result(listOf(row(1, requirements = "{}"))).recommendations.single().score
        val input = row(1).let {
            it.copy(
                entity = it.entity.copy(
                    content = """{
                        "stage_name":"s1","opers":[{"name":"测试干员","skill":3}],
                        "groups":[{"name":"术师位","opers":[{"name":"测试干员","skill":3}]}]
                    }""",
                ),
            )
        }
        listOf(history, history.copy(includeAlternatives = false)).forEach { query ->
            val recommendation = result(listOf(input), query).recommendations.single()
            assertEquals(fixedScore, recommendation.score, 1e-9)
            assertTrue(!recommendation.sources.single().alternative)
        }
    }

    @Test
    fun `videos ambiguous names invalid levels and malformed content cannot become recommendations`() {
        val video = row(1).let { it.copy(entity = it.entity.copy(type = CopilotType.VIDEO)) }
        assertTrue(result(listOf(video)).recommendations.isEmpty())
        assertTrue(result(listOf(row(2, level = 100))).recommendations.isEmpty())
        val malformed = row(3).let { it.copy(entity = it.entity.copy(content = "{")) }
        assertEquals(1, result(listOf(malformed)).invalidCount)
        val ambiguous = RecommendationEngine(listOf(operator, operator.copy(id = "another", role = "Medic")))
        assertTrue(ambiguous.calculate(listOf(row(4)), levels, history, now).recommendations.isEmpty())
    }

    @Test
    fun `operators without active skills still receive level targets`() {
        val robot = RecommendationOperator("robot", "小车", "Medic", 1)
        val input = row(1, name = robot.name, level = 30).let {
            it.copy(
                entity = it.entity.copy(content = """{"stage_name":"s1","opers":[{"name":"小车","requirements":{"elite":0,"level":30}}]}"""),
            )
        }
        val target = RecommendationEngine(
            listOf(robot),
        ).calculate(listOf(input), levels, history, now).recommendations.single().branches.single().target
        assertEquals(0, target.skill)
        assertNull(target.skillLevel)
        assertEquals(30, target.level)
    }

    @Test
    fun `closed activities require explicit opt in`() {
        val activity = ArkLevelEntity(stageId = "s1", levelId = "activities/s1", catOne = "活动关卡", catTwo = "旧活动", isOpen = false)
        assertTrue(engine.calculate(listOf(row(1)), listOf(activity), history, now).recommendations.isEmpty())
        assertEquals(1, engine.calculate(listOf(row(1)), listOf(activity), history.copy(includeClosed = true), now).recommendations.size)
    }
}
