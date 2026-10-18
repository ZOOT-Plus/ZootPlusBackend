package plus.maa.backend.service.recommendation

import com.github.benmanes.caffeine.cache.Cache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.gamedata.ArkCharacter
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.repository.ktorm.RecommendationSnapshotRepository
import plus.maa.backend.service.level.ArkGameDataHolder
import plus.maa.backend.service.level.ArkLevelService
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OperatorRecommendationServiceTest {
    private val copilot = mockk<CopilotRepository>()
    private val ratings = mockk<RatingRepository>()
    private val levels = mockk<ArkLevelRepository>()
    private val levelService = mockk<ArkLevelService>()
    private val snapshots = mockk<RecommendationSnapshotRepository>()
    private val operator = RecommendationOperator("char_103_angel", "能天使", "Sniper", 6)
    private val time = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0)
    private val row = CopilotEntity(
        copilotId = 1,
        stageName = "s1",
        firstUploadTime = time.minusDays(10),
        likeCount = 90,
        dislikeCount = 10,
        content = """{"stage_name":"s1","opers":[{"name":"能天使","skill":3,"requirements":{"elite":2,"level":60,"skill_level":10}}]}""",
    )
    private val catalog = listOf(ArkLevelEntity(stageId = "s1", levelId = "main/s1"))
    private val evidence = RecommendationEngine(listOf(operator)).prepare(listOf(RecommendationInput(row)), catalog)
    private val query = RecommendationQuery(stageId = "s1")

    private fun service(): OperatorRecommendationService = OperatorRecommendationService(copilot, ratings, levels, levelService, snapshots)

    private fun persisted(at: LocalDateTime = time) {
        every { snapshots.generatedAt() } returns at
        every { snapshots.load(at) } returns evidence
        every { snapshots.rebuildIfNeeded(any(), any()) } returns false
    }

    @Test
    fun `restarts reuse persisted evidence without reading or parsing original operations`() {
        persisted()
        val first = service().recommend(query)
        assertEquals(1, first.recommendations.size)
        assertEquals(first, service().recommend(query))
        verify(exactly = 2) { snapshots.load(time) }
        verify(exactly = 0) { copilot.findPublicRecommendationPage(any(), any()) }
        coVerify(exactly = 0) { levelService.gameData() }
    }

    @Test
    fun `equivalent filters reuse a hot result without rereading evidence or its version`() {
        persisted()
        val service = service()
        val first = service.recommend(query)
        assertSame(first, service.recommend(query.copy(stageId = " s1 ", category = "", activity = " ", days = 0)))
        verify(exactly = 1) { snapshots.generatedAt() }
        verify(exactly = 1) { snapshots.load(time) }
        val different = service.recommend(query.copy(minOperationCount = 1))
        assertEquals(listOf("s1"), different.stages.map { it.id })
        assertEquals(first.recommendations, different.recommendations)
    }

    @Test
    fun `only 16 hot results are retained and inactive results expire`() {
        persisted()
        val service = service()
        repeat(64) { service.recommend(query.copy(days = it + 100)) }
        val cache = ReflectionTestUtils.getField(service, "results") as Cache<*, *>
        cache.cleanUp()
        assertTrue(cache.estimatedSize() <= 16)
        assertEquals(Duration.ofMinutes(30), cache.policy().expireAfterAccess().orElseThrow().expiresAfter)
        val first = service.recommend(query)
        assertSame(first, service.recommend(query))
    }

    @Test
    fun `uncached queries have bounded concurrency and identical queries share one load`() {
        persisted()
        val service = service()
        val cached = service.recommend(query)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        every { snapshots.load(time) } answers {
            entered.countDown()
            check(release.await(10, SECONDS))
            evidence
        }
        Executors.newFixedThreadPool(4).use { executor ->
            val first = executor.submit<RecommendationResult> { service.recommend(query.copy(coverage = 0.6)) }
            val second = executor.submit<RecommendationResult> { service.recommend(query.copy(coverage = 0.9)) }
            try {
                assertTrue(entered.await(5, SECONDS))
                val identical = executor.submit<RecommendationResult> { service.recommend(query.copy(coverage = 0.6)) }
                val third = executor.submit<RecommendationResult> { service.recommend(query.copy(coverage = 0.7)) }
                assertThrows(TimeoutException::class.java) { third.get(100, MILLISECONDS) }
                assertSame(cached, service.recommend(query))
                release.countDown()
                assertSame(first.get(5, SECONDS), identical.get(5, SECONDS))
                assertEquals(1, second.get(5, SECONDS).recommendations.size)
                assertEquals(1, third.get(5, SECONDS).recommendations.size)
            } finally {
                release.countDown()
            }
        }
        verify(exactly = 4) { snapshots.load(time) }
    }

    private fun source() {
        every { copilot.findPublicRecommendationPage(0, 1000) } returns listOf(row)
        every { copilot.findPublicRecommendationPage(1, 1000) } returns emptyList()
        every { ratings.latestPositiveCopilotTimes(any()) } returns emptyMap()
        every { levels.findAllOrdered() } returns catalog
        coEvery { levelService.gameData() } returns ArkGameDataHolder(
            emptyMap(),
            emptyMap(),
            emptyMap(),
            mapOf("angel" to ArkCharacter("能天使", "SNIPER", 5).apply { id = operator.id }),
            emptyMap(),
            emptyMap(),
        )
    }

    @Test
    fun `first build runs on a temporary virtual thread and publishes only compact evidence`() {
        source()
        val persisted = AtomicReference<RecommendationEvidence?>()
        val worker = AtomicReference<Thread>()
        every { snapshots.generatedAt() } answers { if (persisted.get() == null) null else time }
        every { snapshots.load(time) } answers { persisted.get() }
        every { snapshots.rebuildIfNeeded(any(), any()) } answers {
            worker.set(Thread.currentThread())
            persisted.set(secondArg<() -> RecommendationEvidence>().invoke())
            true
        }
        val service = service()
        assertEquals(1, service.recommend(query).recommendations.size)
        assertTrue(worker.get().isVirtual)
        await().atMost(Duration.ofSeconds(5)).until { !worker.get().isAlive }
        assertEquals(1, persisted.get()!!.families.single().operationCount)
        verify(exactly = 1) { copilot.findPublicRecommendationPage(0, 1000) }
        assertFalse(service.javaClass.declaredFields.any { it.type == RecommendationEvidence::class.java })
    }

    @Test
    fun `daily rebuild is coalesced while old cached and uncached queries remain readable`() {
        persisted()
        val service = service()
        val cached = service.recommend(query)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freshTime = time.plusSeconds(1)
        every { snapshots.load(freshTime) } returns evidence.copy(families = emptyList())
        every { snapshots.rebuildIfNeeded(any(), any()) } answers {
            entered.countDown()
            check(release.await(10, SECONDS))
            every { snapshots.generatedAt() } returns freshTime
            true
        }
        val pending = service.rebuild()
        try {
            assertTrue(entered.await(5, SECONDS))
            repeat(10) { assertSame(pending, service.rebuild()) }
            assertSame(cached, service.recommend(query))
            assertEquals(1, service.recommend(query.copy(coverage = 0.9)).recommendations.size)
        } finally {
            release.countDown()
            pending.get(5, SECONDS)
        }
        assertTrue(service.recommend(query).recommendations.isEmpty())
        verify(exactly = 1) { snapshots.rebuildIfNeeded(any(), any()) }
    }

    @Test
    fun `old data is served during a failed rebuild and retry is limited by the version cache`() {
        persisted(time.minusDays(3))
        val attempts = AtomicInteger()
        every { snapshots.rebuildIfNeeded(any(), any()) } answers {
            attempts.incrementAndGet()
            throw IllegalStateException("build failed")
        }
        val service = service()
        val result = service.recommend(query)
        assertEquals(time.minusDays(3).toString(), result.generatedAt)
        await().atMost(Duration.ofSeconds(5)).until { attempts.get() == 1 }
        repeat(10) { assertSame(result, service.recommend(query)) }
        assertEquals(1, service.recommend(query.copy(coverage = 0.9)).recommendations.size)
        verify(exactly = 1) { snapshots.rebuildIfNeeded(any(), any()) }
    }

    @Test
    fun `cold build failures are reported and the next request can retry`() {
        every { snapshots.generatedAt() } returns null
        every { snapshots.rebuildIfNeeded(any(), any()) } throws IllegalStateException("database unavailable")
        val service = service()
        assertThrows(java.util.concurrent.CompletionException::class.java) { service.recommend(query) }
        persisted()
        val versions = ReflectionTestUtils.getField(service, "versions") as Cache<*, *>
        versions.invalidateAll()
        assertEquals(1, service.recommend(query).recommendations.size)
    }

    @Test
    fun `replacement between version lookup and evidence read reloads the current version`() {
        persisted()
        val freshTime = time.plusSeconds(1)
        every { snapshots.load(time) } answers {
            every { snapshots.generatedAt() } returns freshTime
            null
        }
        every { snapshots.load(freshTime) } returns evidence
        val result = service().recommend(query)
        assertEquals(freshTime.toString(), result.generatedAt)
        verify(exactly = 1) { snapshots.load(time) }
        verify(exactly = 1) { snapshots.load(freshTime) }
    }

    @Test
    fun `in flight old calculations cannot populate the new generation cache`() {
        persisted()
        val service = service()
        val cached = service.recommend(query)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { snapshots.load(time) } answers {
            entered.countDown()
            check(release.await(10, SECONDS))
            evidence
        }
        val freshTime = time.plusSeconds(1)
        every { snapshots.load(freshTime) } returns evidence.copy(families = emptyList())
        every { snapshots.rebuildIfNeeded(any(), any()) } answers {
            every { snapshots.generatedAt() } returns freshTime
            true
        }
        Executors.newSingleThreadExecutor().use { executor ->
            val pending = executor.submit<RecommendationResult> { service.recommend(query.copy(coverage = 0.9)) }
            try {
                assertTrue(entered.await(5, SECONDS))
                assertSame(cached, service.recommend(query))
                service.rebuild().get(5, SECONDS)
                assertTrue(service.recommend(query).recommendations.isEmpty())
            } finally {
                release.countDown()
            }
            assertEquals(1, pending.get(5, SECONDS).recommendations.size)
            assertTrue(service.recommend(query.copy(coverage = 0.9)).recommendations.isEmpty())
        }
    }
}
