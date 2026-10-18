package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

class RecommendationRepositoryTest : TestDbSupport() {
    @Test
    fun `only public non deleted automatic operations appear across keyset pages`() {
        val repo = CopilotRepository(jdbi)
        val public = repo.insertEntity(CopilotEntity())
        repo.insertEntity(CopilotEntity(status = CopilotSetStatus.PRIVATE))
        repo.insertEntity(CopilotEntity(delete = true))
        repo.insertEntity(CopilotEntity(type = CopilotType.VIDEO))
        val next = repo.insertEntity(CopilotEntity())
        assertEquals(listOf(public.copilotId), repo.findPublicRecommendationPage(0, 1).map { it.copilotId })
        assertEquals(listOf(next.copilotId), repo.findPublicRecommendationPage(public.copilotId, 1).map { it.copilotId })
        assertTrue(repo.findPublicRecommendationPage(next.copilotId, 1).isEmpty())
    }

    @Test
    fun `recent feedback uses current positive ratings only and handles empty keys`() {
        val repo = RatingRepository(jdbi)
        val time = LocalDateTime.of(2026, 7, 1, 0, 0)
        repo.insertEntity(RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "a", rating = RatingType.LIKE, rateTime = time))
        repo.insertEntity(
            RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "b", rating = RatingType.DISLIKE, rateTime = time.plusDays(1)),
        )
        repo.insertEntity(
            RatingEntity(type = Rating.KeyType.COMMENT, key = "1", userId = "a", rating = RatingType.LIKE, rateTime = time.plusDays(2)),
        )
        assertEquals(mapOf("1" to time), repo.latestPositiveCopilotTimes(listOf("1", "2")))
        assertTrue(repo.latestPositiveCopilotTimes(emptyList()).isEmpty())
    }

    @Test
    fun `recommendation projections retain calculation fields without unrelated details`() {
        val repo = CopilotRepository(jdbi)
        val time = LocalDateTime.of(2026, 10, 1, 12, 0)
        val row = repo.insertEntity(
            CopilotEntity(
                stageName = "stage",
                title = "title",
                content = "large document",
                details = "large description",
                firstUploadTime = time,
                likeCount = 31,
                dislikeCount = 2,
                views = 104,
            ),
        )
        val projected = repo.findPublicRecommendationPage(0, 100).single()
        assertEquals(row.content, projected.content)
        assertEquals(row.title, projected.title)
        assertEquals(row.firstUploadTime, projected.firstUploadTime)
        assertEquals(row.views, projected.views)
        assertEquals(row.likeCount, projected.likeCount)
        assertEquals(row.dislikeCount, projected.dislikeCount)
        assertEquals(row.stageName, projected.stageName)
        assertEquals(null, projected.details)
    }
}
