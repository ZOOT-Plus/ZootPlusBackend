package plus.maa.backend.service.recommendation

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

class OperatorRecommendationServiceTest {
    private val copilot = mockk<CopilotRepository>()
    private val ratings = mockk<RatingRepository>()
    private val levels = mockk<ArkLevelRepository>()
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
        return OperatorRecommendationService(copilot, ratings, levels)
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
        val content = row.content
        every { row.content } answers {
            if (blockNext.compareAndSet(true, false)) {
                calculating.countDown()
                check(release.await(10, SECONDS))
            }
            content
        }
        val uncached = query.copy(coverage = 0.9)
        Executors.newFixedThreadPool(2).use { executor ->
            val pending = executor.submit<RecommendationResult> { service.recommend(uncached) }
            try {
                assertTrue(calculating.await(5, SECONDS))
                assertEquals(cached, executor.submit<RecommendationResult> { service.recommend(query) }.get(5, SECONDS))
                val other = executor.submit<RecommendationResult> { service.recommend(query.copy(coverage = 0.6)) }.get(5, SECONDS)
                assertEquals(1, other.recommendations.size)
                every { copilot.findPublicRecommendationPage(0, 1000) } returns emptyList()
                val fresh = executor.submit<RecommendationResult> {
                    service.invalidate()
                    service.recommend(uncached)
                }.get(5, SECONDS)
                assertTrue(fresh.recommendations.isEmpty())
            } finally {
                release.countDown()
            }
            assertEquals(1, pending.get(5, SECONDS).recommendations.size)
            assertTrue(service.recommend(uncached).recommendations.isEmpty())
        }
        verify(exactly = 2) { copilot.findPublicRecommendationPage(0, 1000) }
    }
}
