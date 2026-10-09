package plus.maa.backend.service.recommendation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.service.model.CopilotType
import java.time.LocalDateTime

class RecommendationEngineTest {
    private val now = LocalDateTime.of(2026, 7, 10, 12, 0)
    private val operator = RecommendationOperator("char_test", "测试干员", "Caster", 6, listOf(0, 1, 2))
    private val other = RecommendationOperator("char_other", "备选干员", "Caster", 6, listOf(0, 1, 2))
    private val engine = RecommendationEngine(listOf(operator, other))
    private val levels = (1..4).map {
        ArkLevelEntity(stageId = "s$it", levelId = "main/s$it", catOne = "主题曲", catTwo = "章节", catThree = "1-$it")
    }
    private val history = RecommendationQuery(days = 0, scope = RecommendationScope.HISTORY)

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
    ): RecommendationInput {
        val requirementsJson = requirements ?: """{
            "elite":2,"level":$level,"skill_level":$skillLevel,"module":$module$extra
        }"""
        val content = """{
            "stage_name":"$stage",
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
    fun `prepared feedback refresh matches a full rebuild and leaves the previous snapshot intact`() {
        val original = row(1, age = 400)
        val copy = row(2, age = 380, likes = 10, dislikes = 1, views = 10_000)
        val differentVariant = row(3, age = 390, level = 90)
        val independent = row(4, stage = "s2")
        val invalid = row(5).let { it.copy(entity = it.entity.copy(content = "{")) }
        val rows = listOf(original, copy, differentVariant, independent, invalid)
        val prepared = engine.prepare(rows, levels)
        val queries = RecommendationScope.entries.flatMap { scope ->
            listOf(0, 7, 180).flatMap { days ->
                listOf(0.6, 0.8, 0.9).map { coverage ->
                    RecommendationQuery(scope = scope, days = days, coverage = coverage)
                }
            }
        } + listOf(history.copy(stageId = "s1"), RecommendationQuery(stageId = "s1"), history.copy(category = "主题曲"))
        val initial = queries.map { engine.calculate(prepared, it, now) }
        val feedback = copy.copy(entity = copy.entity.copy(likeCount = 200, views = 20_000), latestPositiveAt = now)
        val refreshed = prepared.withFeedback(feedback)
        assertEquals(2L, refreshed.families.first().variants.first().input.entity.copilotId)
        assertSame(prepared.families.last(), refreshed.families.last())
        queries.forEachIndexed { index, query ->
            assertEquals(result(rows.map { if (it === copy) feedback else it }, query), engine.calculate(refreshed, query, now))
            assertEquals(initial[index], engine.calculate(prepared, query, now))
        }
        val cancelled = feedback.copy(entity = feedback.entity.copy(likeCount = 0, dislikeCount = 0), latestPositiveAt = null)
        val reverted = refreshed.withFeedback(cancelled)
        assertEquals(1L, reverted.families.first().variants.first().input.entity.copilotId)
        queries.forEach { query ->
            assertEquals(result(rows.map { if (it === copy) cancelled else it }, query), engine.calculate(reverted, query, now))
        }
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
        assertTrue(defaultJson.encodeToString(mixed).isNotBlank())
    }

    @Test
    fun `default requires recent independent support while focused mode accepts a specialist`() {
        assertTrue(result(listOf(row(1)), RecommendationQuery()).recommendations.isEmpty())
        assertEquals("CURRENT", result(listOf(row(1)), RecommendationQuery(stageId = "s1")).recommendations.single().status)
        val rows = listOf(row(1), row(2, stage = "s2"), row(3, stage = "s3"))
        assertEquals("CURRENT", result(rows, RecommendationQuery()).recommendations.single().status)
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
