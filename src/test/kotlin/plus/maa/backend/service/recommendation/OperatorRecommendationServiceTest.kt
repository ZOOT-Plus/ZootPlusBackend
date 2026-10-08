package plus.maa.backend.service.recommendation

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import java.time.LocalDateTime

class OperatorRecommendationServiceTest {
    @Test
    fun `invalidation removes cached operations and cached query results`() {
        val copilot = mockk<CopilotRepository>()
        val ratings = mockk<RatingRepository>()
        val levels = mockk<ArkLevelRepository>()
        val row = CopilotEntity(
            copilotId = 1,
            stageName = "s1",
            firstUploadTime = LocalDateTime.now().minusDays(10),
            likeCount = 90,
            dislikeCount = 10,
            content = """{"stage_name":"s1","opers":[{"name":"能天使","skill":3,"requirements":{"elite":2,"level":60,"skill_level":10}}]}""",
        )
        every { copilot.findPublicRecommendationPage(0, 1000) } returns listOf(row)
        every { copilot.findPublicRecommendationPage(1, 1000) } returns emptyList()
        every { ratings.latestPositiveCopilotTimes(any()) } returns emptyMap()
        every { levels.findAllOrdered() } returns listOf(ArkLevelEntity(stageId = "s1", levelId = "main/s1"))
        val service = OperatorRecommendationService(copilot, ratings, levels)
        val query = RecommendationQuery(stageId = "s1")
        assertEquals(1, service.recommend(query).recommendations.size)
        every { copilot.findPublicRecommendationPage(0, 1000) } returns emptyList()
        service.invalidate()
        assertTrue(service.recommend(query).recommendations.isEmpty())
        assertTrue(service.recommend(query.copy(coverage = 0.9)).recommendations.isEmpty())
    }
}
