package plus.maa.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import plus.maa.backend.config.SerializationConfig
import plus.maa.backend.controller.request.copilot.CopilotRatingReq
import plus.maa.backend.controller.request.copilot.PrtsCUDRequest
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.RatingType
import plus.maa.backend.service.recommendation.OperatorRecommendationService
import java.time.LocalDateTime

class CopilotRecommendationInvalidationTest {
    private val repository = mockk<CopilotRepository>(relaxed = true)
    private val ratings = spyk(RatingService(mockk()))
    private val recommendations = mockk<OperatorRecommendationService>(relaxed = true)
    private val levels = mockk<ArkLevelService>(relaxed = true)
    private val row = CopilotEntity(copilotId = 1, uploaderId = 7)
    private val service = CopilotService(
        repository, ratings, SerializationConfig().kotlinJson(), levels, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), recommendations,
    )

    init {
        every { repository.existsByCopilotId(1) } returns true
        every { repository.findNotDeletedCopilotId(1) } returns row
        every { repository.updateEntity(any()) } answers { firstArg() }
    }

    @Test
    fun `unchanged rating skips operation writes and recommendation refresh`() {
        val rating =
            RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "7", rating = RatingType.LIKE, rateTime = LocalDateTime.now())
        every { ratings.rateCopilot(1, "7", RatingType.LIKE) } returns (rating to rating)
        service.rates("7", CopilotRatingReq(1, "Like"))
        verify(exactly = 1) { repository.findNotDeletedCopilotId(1) }
        verify(exactly = 0) { repository.updateEntity(any()) }
        verify(exactly = 0) { recommendations.refreshFeedback(any()) }
        verify(exactly = 0) { recommendations.invalidate() }
    }

    @Test
    fun `unchanged rating still rejects a deleted operation`() {
        val rating =
            RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "7", rating = RatingType.LIKE, rateTime = LocalDateTime.now())
        every { ratings.rateCopilot(1, "7", RatingType.LIKE) } returns (rating to rating)
        every { repository.findNotDeletedCopilotId(1) } returns null
        assertThrows(IllegalStateException::class.java) { service.rates("7", CopilotRatingReq(1, "Like")) }
        verify(exactly = 0) { recommendations.refreshFeedback(any()) }
    }

    @Test
    fun `changed rating refreshes feedback after persisting counts`() {
        val previous =
            RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "7", rating = RatingType.NONE, rateTime = LocalDateTime.now())
        every { ratings.rateCopilot(1, "7", RatingType.LIKE) } returns (previous to previous.copy(rating = RatingType.LIKE))
        every { recommendations.refreshFeedback(1) } answers { assertEquals(1L, row.likeCount) }
        service.rates("7", CopilotRatingReq(1, "Like"))
        assertEquals(1L, row.likeCount)
        verify(exactly = 1) { repository.updateEntity(row) }
        verify(exactly = 1) { recommendations.refreshFeedback(1) }
        verify(exactly = 0) { recommendations.invalidate() }
    }

    @Test
    fun `notification and comment switches keep recommendation caches`() {
        service.notificationStatus(7, 1, true)
        service.commentStatus(7, 1, CommentStatus.DISABLED)
        assertEquals(true, row.notification)
        assertEquals(CommentStatus.DISABLED, row.commentStatus)
        verify(exactly = 2) { repository.updateEntity(row) }
        verify(exactly = 0) { recommendations.invalidate() }
        verify(exactly = 0) { recommendations.refreshFeedback(any()) }
    }

    @Test
    fun `upload edit visibility and delete invalidate recommendation structure`() {
        every { levels.findByLevelIdFuzzy(any()) } returns null
        every { repository.insertEntity(any()) } answers {
            firstArg<CopilotEntity>().apply { copilotId = 2 }
        }
        val request = PrtsCUDRequest(content = """{"stage_name":"s1","minimum_required":"v4.0.0","opers":[],"actions":[]}""", id = 1)
        service.upload(7, request)
        service.update(7, request)
        service.update(7, request.copy(status = CopilotSetStatus.PRIVATE))
        service.delete(7, 1)
        verify(exactly = 4) { recommendations.invalidate() }
        verify(exactly = 0) { recommendations.refreshFeedback(any()) }
    }
}
