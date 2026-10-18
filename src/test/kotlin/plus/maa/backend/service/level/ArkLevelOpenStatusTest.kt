package plus.maa.backend.service.level

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.common.utils.converter.ArkLevelEntityConverter
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.gamedata.ArkCharacter
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.repository.ktorm.RecommendationSnapshotRepository
import plus.maa.backend.service.recommendation.OperatorRecommendationService
import plus.maa.backend.service.recommendation.RecommendationQuery
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertFailsWith

class ArkLevelOpenStatusTest : TestDbSupport() {
    private val levels = ArkLevelRepository(jdbi)
    private val copilot = CopilotRepository(jdbi)
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val recommendations = OperatorRecommendationService(
        copilot,
        RatingRepository(jdbi),
        levels,
        mockk<ArkLevelService> {
            coEvery { gameData() } returns ArkGameDataHolder(
                emptyMap(),
                emptyMap(),
                emptyMap(),
                mapOf("angel" to ArkCharacter("能天使", "SNIPER", 5).apply { id = "char_103_angel" }),
                emptyMap(),
                emptyMap(),
            )
        },
        RecommendationSnapshotRepository(jdbi),
    )

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

    private fun refreshRecommendations() {
        jdbi.useHandle<Exception> {
            it.execute("UPDATE recommendation_snapshot SET generated_at = generated_at - INTERVAL '1 day'")
        }
        recommendations.rebuild().get(5, SECONDS)
    }

    @Test
    fun `activity and crisis status changes wait for the daily snapshot refresh`() = runTest {
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
            refreshRecommendations()
            val query = RecommendationQuery(stageId = stageId)
            val closed = recommendations.recommend(query)
            assertTrue(closed.recommendations.isEmpty())
            service.updateLevelsOfTypeInBatch(type) { it.isOpen = true }
            assertSame(closed, recommendations.recommend(query))
            refreshRecommendations()
            val open = recommendations.recommend(query)
            assertEquals(1, open.recommendations.size)
            service.updateLevelsOfTypeInBatch(type) { it.isOpen = true }
            service.updateLevelsOfTypeInBatch(type) { it.isOpen = false }
            assertSame(open, recommendations.recommend(query))
            refreshRecommendations()
            assertTrue(recommendations.recommend(query).recommendations.isEmpty())
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `completed pages remain persisted if a later page fails`() = runTest {
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
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }
}
