package plus.maa.backend.service.level

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.common.utils.converter.ArkLevelEntityConverter
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.service.recommendation.OperatorRecommendationService
import plus.maa.backend.service.recommendation.RecommendationQuery
import kotlin.test.assertFailsWith

class ArkLevelOpenStatusTest : TestDbSupport() {
    private val levels = ArkLevelRepository(jdbi)
    private val copilot = CopilotRepository(jdbi)
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val recommendations = OperatorRecommendationService(copilot, RatingRepository(jdbi), levels)

    private fun service(repository: ArkLevelRepository = levels) = ArkLevelService(
        properties = MaaCopilotProperties(),
        githubRepo = mockk(),
        redisCache = mockk(),
        arkLevelRepo = repository,
        json = defaultJson,
        arkLevelConverter = mockk(),
        arkLevelEntityConverter = ArkLevelEntityConverter(),
        eventPublisher = publisher,
    )

    @Test
    fun `activity and crisis status changes invalidate cached recommendations`() = runTest {
        every { publisher.publishEvent(ArkLevelOpenStatusChangedEvent) } answers {
            recommendations.onOpenStatusChanged(ArkLevelOpenStatusChangedEvent)
        }
        val service = service()
        listOf(ArkLevelType.ACTIVITIES to "activities", ArkLevelType.RUNE to "rune").forEachIndexed { index, (type, path) ->
            val stageId = "s$index"
            levels.insertEntity(ArkLevelEntity(stageId = stageId, levelId = "$path/$stageId", catOne = type.display, isOpen = false))
            copilot.insertEntity(
                CopilotEntity(
                    stageName = stageId,
                    firstUploadTime = java.time.LocalDateTime.now().minusDays(10),
                    likeCount = 90,
                    dislikeCount = 10,
                    content = """{"stage_name":"$stageId","opers":[{"name":"能天使","skill":3}]}""",
                ),
            )
            val query = RecommendationQuery(stageId = stageId)
            assertTrue(recommendations.recommend(query).recommendations.isEmpty())
            service.updateLevelsOfTypeInBatch(type) { it.isOpen = true }
            assertEquals(1, recommendations.recommend(query).recommendations.size)
            service.updateLevelsOfTypeInBatch(type) { it.isOpen = true }
            service.updateLevelsOfTypeInBatch(type) { it.isOpen = false }
            assertTrue(recommendations.recommend(query).recommendations.isEmpty())
            verify(exactly = (index + 1) * 2) { publisher.publishEvent(ArkLevelOpenStatusChangedEvent) }
        }
        verify(exactly = 0) { publisher.publishEvent(ArkLevelsSyncedEvent) }
    }

    @Test
    fun `completed pages still invalidate caches if a later page fails`() = runTest {
        val first = levels.insertEntity(ArkLevelEntity(catOne = ArkLevelType.ACTIVITIES.display, isOpen = false))
        val second = levels.insertEntity(ArkLevelEntity(catOne = ArkLevelType.ACTIVITIES.display, isOpen = false))
        val repository = spyk(levels)
        every { repository.saveAll(any()) } answers {
            check(firstArg<List<ArkLevelEntity>>().first().id != second.id) { "write failed" }
            callOriginal()
        }
        assertFailsWith<IllegalStateException> {
            service(repository).updateLevelsOfTypeInBatch(ArkLevelType.ACTIVITIES, batchSize = 1) { it.isOpen = true }
        }
        assertTrue(levels.findById(first.id)!!.isOpen!!)
        assertFalse(levels.findById(second.id)!!.isOpen!!)
        verify(exactly = 1) { publisher.publishEvent(ArkLevelOpenStatusChangedEvent) }
    }
}
