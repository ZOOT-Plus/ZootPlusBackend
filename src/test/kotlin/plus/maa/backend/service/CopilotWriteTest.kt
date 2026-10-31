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
import plus.maa.backend.service.model.CopilotType
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

class CopilotWriteTest {
    private val repository = mockk<CopilotRepository>(relaxed = true)
    private val ratings = spyk(RatingService(mockk()))
    private val levels = mockk<ArkLevelService>(relaxed = true)
    private val row = CopilotEntity(copilotId = 1, uploaderId = 7)
    private val service = CopilotService(
        repository, ratings, SerializationConfig().kotlinJson(), levels, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(), mockk(relaxed = true),
        mockk(relaxed = true),
    )

    init {
        every { repository.existsByCopilotId(1) } returns true
        every { repository.findNotDeletedCopilotId(1) } returns row
        every { repository.updateEntity(any()) } answers { firstArg() }
    }

    @Test
    fun `unchanged rating skips operation writes`() {
        val rating =
            RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "7", rating = RatingType.LIKE, rateTime = LocalDateTime.now())
        every { ratings.rateCopilot(1, "7", RatingType.LIKE) } returns (rating to rating)
        service.rates("7", CopilotRatingReq(1, "Like"))
        verify(exactly = 1) { repository.findNotDeletedCopilotId(1) }
        verify(exactly = 0) { repository.updateEntity(any()) }
    }

    @Test
    fun `unchanged rating still rejects a deleted operation`() {
        val rating =
            RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "7", rating = RatingType.LIKE, rateTime = LocalDateTime.now())
        every { ratings.rateCopilot(1, "7", RatingType.LIKE) } returns (rating to rating)
        every { repository.findNotDeletedCopilotId(1) } returns null
        assertThrows(IllegalStateException::class.java) { service.rates("7", CopilotRatingReq(1, "Like")) }
    }

    @Test
    fun `changed rating persists counts`() {
        val previous =
            RatingEntity(type = Rating.KeyType.COPILOT, key = "1", userId = "7", rating = RatingType.NONE, rateTime = LocalDateTime.now())
        every { ratings.rateCopilot(1, "7", RatingType.LIKE) } returns (previous to previous.copy(rating = RatingType.LIKE))
        service.rates("7", CopilotRatingReq(1, "Like"))
        assertEquals(1L, row.likeCount)
        verify(exactly = 1) { repository.updateEntity(row) }
    }

    @Test
    fun `notification and comment switches persist their changes`() {
        service.notificationStatus(7, 1, true)
        service.commentStatus(7, 1, CommentStatus.DISABLED)
        assertEquals(true, row.notification)
        assertEquals(CommentStatus.DISABLED, row.commentStatus)
        verify(exactly = 2) { repository.updateEntity(row) }
    }

    @Test
    fun `uploads and public edits persist their changes`() {
        every { levels.findByLevelIdFuzzy(any()) } returns null
        every { repository.insertEntity(any()) } answers {
            firstArg<CopilotEntity>().apply { copilotId = 2 }
        }
        val request = PrtsCUDRequest(content = """{"stage_name":"s1","minimum_required":"v4.0.0","opers":[],"actions":[]}""", id = 1)
        service.upload(7, request)
        service.update(7, request)
        verify(exactly = 1) { repository.insertEntity(any()) }
        verify(exactly = 1) { repository.updateEntity(row) }
    }

    @Test
    fun `restricting public operation visibility persists the private status`() {
        every { levels.findByLevelIdFuzzy(any()) } returns null
        val request = PrtsCUDRequest(
            content = """{"stage_name":"s1","minimum_required":"v4.0.0","opers":[],"actions":[]}""",
            id = 1,
            status = CopilotSetStatus.PRIVATE,
        )
        service.update(7, request)
        verify(exactly = 1) { repository.updateEntity(row) }
        assertEquals(CopilotSetStatus.PRIVATE, row.status)
    }

    @Test
    fun `deleting a public automatic operation persists the deletion`() {
        service.delete(7, 1)
        verify(exactly = 1) { repository.updateEntity(row) }
        assertEquals(true, row.delete)
    }

    @Test
    fun `deleting private or video operations persists the deletion`() {
        row.status = CopilotSetStatus.PRIVATE
        service.delete(7, 1)
        row.delete = false
        row.status = CopilotSetStatus.PUBLIC
        row.type = CopilotType.VIDEO
        service.delete(7, 1)
        verify(exactly = 2) { repository.updateEntity(row) }
        assertEquals(true, row.delete)
    }
}
