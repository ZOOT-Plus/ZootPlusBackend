package plus.maa.backend.service.recommendation

import com.github.benmanes.caffeine.cache.Caffeine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.gamedata.ArkCharacter
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.service.level.ArkGameDataHolder
import plus.maa.backend.service.level.ArkLevelService
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OperatorRecommendationServiceTest {
    private val copilot = mockk<CopilotRepository>()
    private val ratings = mockk<RatingRepository>()
    private val levels = mockk<ArkLevelRepository>()
    private val levelService = mockk<ArkLevelService>()
    private val gameData = ArkGameDataHolder(
        emptyMap(),
        emptyMap(),
        emptyMap(),
        mapOf("angel" to ArkCharacter("能天使", "SNIPER", 5).apply { id = "char_103_angel" }),
        emptyMap(),
        emptyMap(),
    )
    private val row = spyk(
        CopilotEntity(
            copilotId = 1,
            stageName = "s1",
            firstUploadTime = LocalDateTime.now().minusDays(10),
            likeCount = 90,
            dislikeCount = 10,
            content = """{"stage_name":"s1","opers":[{"name":"能天使","skill":3,"requirements":{"elite":2,"level":60,"skill_level":10}}]}""",
        ),
    )
    private val query = RecommendationQuery(stageId = "s1")

    private fun service(): OperatorRecommendationService {
        every { copilot.findPublicRecommendationPage(0, 1000) } returns listOf(row)
        every { copilot.findPublicRecommendationPage(1, 1000) } returns emptyList()
        every { ratings.latestPositiveCopilotTimes(any()) } returns emptyMap()
        every { levels.findAllOrdered() } returns listOf(ArkLevelEntity(stageId = "s1", levelId = "main/s1"))
        coEvery { levelService.gameData() } returns gameData
        return OperatorRecommendationService(copilot, ratings, levels, levelService)
    }

    @Test
    fun `invalidation removes cached operations and cached query results`() {
        val service = service()
        assertEquals(1, service.recommend(query).recommendations.size)
        every { copilot.findPublicRecommendationPage(0, 1000) } returns emptyList()
        service.invalidate()
        assertTrue(service.recommend(query).recommendations.isEmpty())
        assertTrue(service.recommend(query.copy(coverage = 0.9)).recommendations.isEmpty())
    }

    @Test
    fun `calculations allow other queries and invalidation without caching stale results`() {
        val service = service()
        val cached = service.recommend(query)
        val calculating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNext = AtomicBoolean(true)
        val likes = row.likeCount
        every { row.likeCount } answers {
            if (blockNext.compareAndSet(true, false)) {
                calculating.countDown()
                check(release.await(10, SECONDS))
            }
            likes
        }
        val uncached = query.copy(coverage = 0.9)
        Executors.newFixedThreadPool(2).use { executor ->
            val pending = executor.submit<RecommendationResult> { service.recommend(uncached) }
            try {
                assertTrue(calculating.await(5, SECONDS))
                assertEquals(cached, executor.submit<RecommendationResult> { service.recommend(query) }.get(5, SECONDS))
                val other = executor.submit<RecommendationResult> { service.recommend(query.copy(coverage = 0.6)) }.get(5, SECONDS)
                assertEquals(1, other.recommendations.size)
                every { copilot.findNotDeletedCopilotId(1) } returns row.copy(likeCount = 1, dislikeCount = 99)
                val fresh = executor.submit<RecommendationResult> {
                    service.refreshFeedback(1)
                    service.recommend(uncached)
                }.get(5, SECONDS)
                assertTrue(fresh.recommendations.isEmpty())
                assertTrue(service.recommend(query).recommendations.isEmpty())
            } finally {
                release.countDown()
            }
            assertEquals(1, pending.get(5, SECONDS).recommendations.size)
            assertTrue(service.recommend(uncached).recommendations.isEmpty())
        }
        every { copilot.findPublicRecommendationPage(0, 1000) } returns emptyList()
        service.invalidate()
        assertTrue(service.recommend(query).recommendations.isEmpty())
        verify(exactly = 2) { copilot.findPublicRecommendationPage(0, 1000) }
    }

    @Test
    fun `queries reuse parsing and feedback refresh only reads the affected operation`() {
        val service = service()
        val initial = service.recommend(query)
        assertEquals(initial, service.recommend(query.copy(coverage = 0.9)))
        verify(exactly = 1) { row.content }
        every { copilot.findNotDeletedCopilotId(1) } returns row.copy(likeCount = 1, dislikeCount = 99)

        service.refreshFeedback(1)

        assertTrue(service.recommend(query).recommendations.isEmpty())
        assertTrue(service.recommend(query.copy(coverage = 0.9)).recommendations.isEmpty())
        verify(exactly = 1) { copilot.findPublicRecommendationPage(0, 1000) }
        verify(exactly = 1) { levels.findAllOrdered() }
        verify(exactly = 1) { copilot.findNotDeletedCopilotId(1) }
        verify(exactly = 2) { ratings.latestPositiveCopilotTimes(listOf("1")) }
        verify(exactly = 1) { row.content }
    }

    @Test
    fun `feedback refresh keeps the original snapshot expiry`() {
        val service = service()
        val ticks = AtomicLong()
        ReflectionTestUtils.setField(
            service,
            "snapshots",
            Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(15)).ticker { ticks.get() }.build<Int, Any>(),
        )
        val initial = service.recommend(query)
        ticks.set(Duration.ofMinutes(14).toNanos())
        every { copilot.findNotDeletedCopilotId(1) } returns row.copy(likeCount = 1, dislikeCount = 99)
        service.refreshFeedback(1)
        assertTrue(service.recommend(query).recommendations.isEmpty())

        ticks.set(Duration.ofMinutes(15).toNanos())
        assertEquals(initial.recommendations, service.recommend(query).recommendations)
        verify(exactly = 2) { copilot.findPublicRecommendationPage(0, 1000) }
    }

    @Test
    fun `feedback changes without a snapshot or for an excluded operation do not load data`() {
        val service = service()
        service.refreshFeedback(1)
        verify(exactly = 0) { copilot.findPublicRecommendationPage(any(), any()) }
        service.recommend(query)
        service.refreshFeedback(2)
        verify(exactly = 0) { copilot.findNotDeletedCopilotId(any()) }
        verify(exactly = 1) { copilot.findPublicRecommendationPage(0, 1000) }
    }

    @Test
    fun `new game data is used when the recommendation snapshot is rebuilt`() {
        val service = service()
        every { copilot.findPublicRecommendationPage(0, 1000) } returns listOf(row.copy(content = row.content.replace("能天使", "新干员")))
        assertTrue(service.recommend(query).recommendations.isEmpty())
        coEvery { levelService.gameData() } returns ArkGameDataHolder(
            emptyMap(),
            emptyMap(),
            emptyMap(),
            mapOf("new" to ArkCharacter("新干员", "SNIPER", 5).apply { id = "char_new" }),
            emptyMap(),
            emptyMap(),
        )
        service.invalidate()
        val recommendation = service.recommend(query).recommendations.single()
        assertEquals("char_new", recommendation.operator.id)
        assertEquals(6, recommendation.operator.rarity)
    }

    @Test
    fun `feedback refresh discards a snapshot if the operation has been removed`() {
        val service = service()
        service.recommend(query)
        every { copilot.findNotDeletedCopilotId(1) } returns null
        every { copilot.findPublicRecommendationPage(0, 1000) } returns emptyList()
        service.refreshFeedback(1)
        assertTrue(service.recommend(query).recommendations.isEmpty())
        verify(exactly = 2) { copilot.findPublicRecommendationPage(0, 1000) }
    }
}
