package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.service.recommendation.RecommendationEngine
import plus.maa.backend.service.recommendation.RecommendationInput
import plus.maa.backend.service.recommendation.RecommendationOperator
import plus.maa.backend.service.recommendation.RecommendationQuery
import plus.maa.backend.service.recommendation.RecommendationScope
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RecommendationSnapshotRepositoryTest : TestDbSupport() {
    private val repo = RecommendationSnapshotRepository(jdbi)
    private val time = LocalDateTime.of(2026, 10, 19, 0, 0, 0, 123456000)
    private val engine = RecommendationEngine(listOf(RecommendationOperator("char_test", "测试干员", "Caster", 6)))
    private val input = RecommendationInput(
        CopilotEntity(
            copilotId = 1,
            stageName = "s1",
            firstUploadTime = time.minusDays(10),
            title = "作业",
            likeCount = 90,
            dislikeCount = 10,
            content = """{"stage_name":"s1","opers":[{"name":"测试干员","skill":3}],"doc":{"details":"not persisted"}}""",
        ),
        time.minusDays(1).withNano(123456789),
    )
    private val evidence = engine.prepare(listOf(input), listOf(ArkLevelEntity(stageId = "s1", levelId = "main/s1")))

    @Test
    fun `snapshots survive repository recreation with identical evidence and calculations`() {
        assertNull(repo.generatedAt())
        assertTrue(repo.rebuildIfNeeded(time) { evidence })
        val restarted = RecommendationSnapshotRepository(jdbi)
        assertEquals(time, restarted.generatedAt())
        val restored = restarted.load(time)!!
        assertEquals(evidence, restored)
        val query = RecommendationQuery(scope = RecommendationScope.HISTORY, days = 0)
        assertEquals(engine.calculate(evidence, query, time), RecommendationEngine().calculate(restored, query, time))
        assertFalse(repo.rebuildIfNeeded(time.plusHours(20)) { error("same day must not rebuild") })
    }

    @Test
    fun `failed build keeps the last complete snapshot and allows a later retry`() {
        repo.rebuildIfNeeded(time) { evidence }
        assertThrows(IllegalStateException::class.java) {
            repo.rebuildIfNeeded(time.plusDays(1)) { error("source unavailable") }
        }
        assertEquals(time, repo.generatedAt())
        assertEquals(evidence, repo.load(time))
        val fresh = evidence.copy(families = emptyList())
        assertTrue(repo.rebuildIfNeeded(time.plusDays(1)) { fresh })
        assertEquals(fresh, repo.load(time.plusDays(1)))
        assertNull(repo.load(time))
    }

    @Test
    fun `concurrent instances build once while readers continue to see the previous complete snapshot`() {
        repo.rebuildIfNeeded(time) { evidence }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val fresh = evidence.copy(families = emptyList())
        Executors.newFixedThreadPool(2).use { executor ->
            val first = executor.submit<Boolean> {
                repo.rebuildIfNeeded(time.plusDays(1)) {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, SECONDS))
                    fresh
                }
            }
            try {
                assertTrue(entered.await(5, SECONDS))
                val second = executor.submit<Boolean> {
                    RecommendationSnapshotRepository(jdbi).rebuildIfNeeded(time.plusDays(1)) {
                        calls.incrementAndGet()
                        evidence
                    }
                }
                assertThrows(TimeoutException::class.java) { second.get(100, MILLISECONDS) }
                assertEquals(time, repo.generatedAt())
                assertEquals(evidence, repo.load(time))
                release.countDown()
                assertTrue(first.get(5, SECONDS))
                assertFalse(second.get(5, SECONDS))
            } finally {
                release.countDown()
            }
        }
        assertEquals(1, calls.get())
        assertEquals(fresh, repo.load(time.plusDays(1)))
    }

    @Test
    fun `incompatible evidence is rebuilt even on the same day`() {
        repo.rebuildIfNeeded(time) { evidence }
        jdbi.useHandle<Exception> { it.execute("UPDATE recommendation_snapshot SET format_version = 0") }
        assertNull(repo.generatedAt())
        assertNull(repo.load(time))
        assertTrue(repo.rebuildIfNeeded(time.plusHours(1)) { evidence })
        assertEquals(evidence, repo.load(time.plusHours(1)))
    }

    @Test
    fun `source deletion or private status does not change stored evidence before the next build`() {
        val source = CopilotRepository(jdbi)
        source.insertEntity(input.entity.copy(copilotId = 0))
        repo.rebuildIfNeeded(time) { evidence }
        jdbi.useHandle<Exception> { it.execute("UPDATE copilot SET status = 'PRIVATE', \"delete\" = TRUE") }
        assertFalse(repo.rebuildIfNeeded(time.plusHours(1)) { error("must wait until tomorrow") })
        assertEquals(evidence, repo.load(time))
        assertTrue(
            repo.rebuildIfNeeded(time.plusDays(1)) {
                engine.prepare(source.findPublicRecommendationPage(0, 1000).map { RecommendationInput(it) }, emptyList())
            },
        )
        assertTrue(repo.load(time.plusDays(1))!!.families.isEmpty())
    }
}
