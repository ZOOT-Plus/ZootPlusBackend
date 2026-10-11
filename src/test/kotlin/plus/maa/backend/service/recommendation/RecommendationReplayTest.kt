package plus.maa.backend.service.recommendation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.readText
import kotlin.io.path.useLines
import kotlin.io.path.writeText
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/** Opt-in full-corpus check; caches stay outside Git and no database or network is used. */
@EnabledIfEnvironmentVariable(named = "RECOMMENDATION_REPLAY_DIR", matches = ".+")
class RecommendationReplayTest {
    @Test
    fun `replay complete operations and measure preparation queries and feedback`() {
        val directory = Path.of(System.getenv("RECOMMENDATION_REPLAY_DIR"))
        val json = Json { ignoreUnknownKeys = true }
        val operators = json.decodeFromString<List<RecommendationOperator>>(directory.resolve("operators.json").readText())
        val levels = json.parseToJsonElement(directory.resolve("levels.json").readText()).jsonArray.map { value ->
            val row = value.jsonObject
            ArkLevelEntity(
                stageId = row.text("stage_id"),
                levelId = row.text("level_id"),
                catOne = row.text("cat_one"),
                catTwo = row.text("cat_two"),
                catThree = row.text("cat_three"),
                name = row.text("name"),
                isOpen = row.text("is_open")?.toBooleanStrictOrNull(),
            )
        }
        val rows = directory.resolve("inputs.jsonl").useLines { lines ->
            lines.map { line ->
                val row = json.parseToJsonElement(line).jsonObject
                RecommendationInput(
                    CopilotEntity(
                        copilotId = row.getValue("id").jsonPrimitive.long,
                        content = row.getValue("content").toString(),
                        stageName = row.text("stageId").orEmpty(),
                        title = row.text("title").orEmpty(),
                        firstUploadTime = LocalDateTime.parse(row.text("publishedAt")),
                        likeCount = row.getValue("likes").jsonPrimitive.long,
                        dislikeCount = row.getValue("dislikes").jsonPrimitive.long,
                        views = row.getValue("views").jsonPrimitive.long,
                    ),
                )
            }.toList()
        }
        val engine = RecommendationEngine(operators)
        val now = LocalDateTime.parse(System.getenv("RECOMMENDATION_REPLAY_TIME") ?: "2026-10-11T12:00:00")
        val report = StringBuilder("rows=${rows.size}, levels=${levels.size}, operators=${operators.size}\n")
        var prepared: RecommendationEngine.Prepared? = null
        measure("prepare", report, warmups = 1, runs = 5) { prepared = engine.prepare(rows, levels) }
        val snapshot = checkNotNull(prepared)
        val popularStage = rows.groupingBy { it.entity.stageName }.eachCount().maxBy { it.value }.key
        val queries = linkedMapOf(
            "recent" to RecommendationQuery(),
            "permanent" to RecommendationQuery(scope = RecommendationScope.PERMANENT),
            "history" to RecommendationQuery(scope = RecommendationScope.HISTORY, days = 0, includeClosed = true),
            "uncertain" to RecommendationQuery(includeUncertain = true),
            "stage" to RecommendationQuery(scope = RecommendationScope.HISTORY, days = 0, stageId = popularStage),
        )
        queries.forEach { (name, query) ->
            lateinit var result: RecommendationResult
            measure(name, report) { result = engine.calculate(snapshot, query, now) }
            assertTrue(result.recommendations.all { it.score.isFinite() && it.score in 0.0..100.0 })
            assertTrue(
                result.recommendations.all { item ->
                    item.branches.isNotEmpty() && item.branches.all { branch ->
                        branch.coverage + 1e-9 >= query.coverage && branch.coverage <= 1.0 + 1e-9 &&
                            branch.usageShare in 0.0..1.000000001
                    }
                },
            )
            assertTrue(result.recommendations.map { it.operator.id }.distinct().size == result.recommendations.size)
            assertTrue(
                result.recommendations.all { item ->
                    val shares = item.branches.distinctBy { it.target.skill to it.target.module }.sumOf { it.usageShare }
                    kotlin.math.abs(shares - 1.0) < 1e-9 && item.sources.map { it.id }.distinct().size == item.sources.size
                },
            )
            directory.resolve("$name-result.json").writeText(json.encodeToString(result))
            report.appendLine(
                "$name: operations=${result.operationCount}, families=${result.familyCount}, " +
                    "invalid=${result.invalidCount}, operators=${result.recommendations.size}, " +
                    "branches=${result.recommendations.sumOf { it.branches.size }}",
            )
        }
        val changed = rows.first { it.entity.copilotId in snapshot.familyIndexById }.let {
            it.copy(entity = it.entity.copy(likeCount = it.entity.likeCount + 1), latestPositiveAt = now)
        }
        measure("feedback", report, runs = 25) { snapshot.withFeedback(changed) }
        directory.resolve("measurements.txt").writeText(report.toString())
        println(report)
    }

    private fun measure(name: String, report: StringBuilder, warmups: Int = 2, runs: Int = 9, block: () -> Unit) {
        repeat(warmups) { block() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val allocations = mutableListOf<Double>()
        val samples = List(runs) {
            val start = bean.getThreadAllocatedBytes(thread)
            val elapsed = measureNanoTime(block) / 1_000_000.0
            allocations.add((bean.getThreadAllocatedBytes(thread) - start) / 1048576.0)
            elapsed
        }.sorted()
        report.appendLine(
            "$name ms: median=${samples[runs / 2]}, p95=${samples[ceil(runs * 0.95).toInt() - 1]}, " +
                "allocatedMiB=${allocations.sorted()[runs / 2]}",
        )
    }

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content?.takeUnless { it == "null" }
}
