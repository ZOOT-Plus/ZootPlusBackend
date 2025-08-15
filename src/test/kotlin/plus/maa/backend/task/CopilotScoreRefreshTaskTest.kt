package plus.maa.backend.task

import io.mockk.every
import io.mockk.mockk
import org.bson.Document
import org.junit.jupiter.api.Test
import org.ktorm.database.Database
import org.ktorm.dsl.batchUpdate
import org.ktorm.entity.EntitySequence
import org.ktorm.entity.count
import org.ktorm.entity.filter
import org.ktorm.entity.toList
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doNothing
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.AggregationResults
import plus.maa.backend.repository.CopilotRepo
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.Copilot
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.Copilots
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.copilots
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.model.RatingCount
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class CopilotScoreRefreshTaskTest {
    private val database: Database = mockk<Database>()
    private val copilotRepo = mockk<CopilotRepo>()
    private val mongoTemplate = mockk<MongoTemplate>()
    private val redisCache = mockk<RedisCache>()
    private val arkLevelService = mockk<ArkLevelService>()
    private val refreshTask: CopilotScoreRefreshTask = CopilotScoreRefreshTask(
        copilotRepo,
        database,
        arkLevelService,
        redisCache,
        mongoTemplate,
    )

    @Test
    fun testRefreshScores() {
        val now = LocalDateTime.now()
        val copilot1 = CopilotEntity { title = "test" }
        copilot1.copilotId = 1L
        copilot1.views = 100L
        copilot1.uploadTime = now
        copilot1.stageName = "stage1"
        val copilot2 = CopilotEntity { title = "test" }
        copilot2.copilotId = 2L
        copilot2.views = 200L
        copilot2.uploadTime = now
        copilot2.stageName = "stage2"
        val copilot3 = CopilotEntity { title = "test" }
        copilot3.copilotId = 3L
        copilot3.views = 200L
        copilot3.uploadTime = now
        copilot3.stageName = "stage3"
        val allCopilots = listOf(copilot1, copilot2, copilot3)

        val query: EntitySequence<CopilotEntity, Copilots> = mockk<EntitySequence<CopilotEntity, Copilots>>()
        // mock 数据库返回
        every { database.copilots.withExpression(any()) } returns query
        every { query.count() } returns 3
        every { query.toList() } returns allCopilots

        // 配置mongoTemplate
        every {
            mongoTemplate.aggregate(any(), Rating::class.java, RatingCount::class.java)
        } returns AggregationResults(
            listOf(
                RatingCount("1", 1L),
                RatingCount("2", 0L),
                RatingCount("3", 0L),
            ),
            Document(),
        )

        val arkLevel = ArkLevel()
        arkLevel.isOpen = true
        arkLevel.closeTime = LocalDateTime.now().plus(1, ChronoUnit.DAYS)
        every { arkLevelService.findByLevelIdFuzzy(any()) } returns arkLevel
        doNothing().`when`(database).batchUpdate(Copilots) { any<Iterable<Copilot>>() }
        every { redisCache.syncRemoveCacheByPattern(any()) } returns Unit
        refreshTask.refreshHotScores()

        check(copilot1.hotScore > 0)
        check(copilot2.hotScore > 0)
    }

    @Test
    fun testRefreshTop100HotScores() {
        val now = LocalDateTime.now()
        val copilot1 = CopilotEntity{title = "test"}
        copilot1.copilotId = 1L
        copilot1.views = 100L
        copilot1.uploadTime = now
        copilot1.stageName = "stage1"
        val copilot2 = CopilotEntity{ title = "test" }
        copilot2.copilotId = 2L
        copilot2.views = 200L
        copilot2.uploadTime = now
        copilot2.stageName = "stage2"
        val copilot3 = CopilotEntity{ title = "test" }
        copilot3.copilotId = 3L
        copilot3.views = 200L
        copilot3.uploadTime = now
        copilot3.stageName = "stage3"
        val allCopilots = listOf(copilot1, copilot2, copilot3)

        // 配置 RedisCache
        every { redisCache.getZSetReverse("rate:hot:copilotIds", 0, 99) } returns setOf("1", "2", "3")

        val query: EntitySequence<CopilotEntity, Copilots> = mockk<EntitySequence<CopilotEntity, Copilots>>()
        // mock 数据库返回
        every { database.copilots.filter { any() } } returns query
        every { query.count() } returns 3
        every { query.toList() } returns allCopilots

        // 配置mongoTemplate
        every {
            mongoTemplate.aggregate(any(), Rating::class.java, RatingCount::class.java)
        } returns AggregationResults(
            listOf(
                RatingCount("1", 1L),
                RatingCount("2", 0L),
                RatingCount("3", 0L),
            ),
            Document(),
        )
        val arkLevel = ArkLevel()
        arkLevel.isOpen = true
        arkLevel.closeTime = LocalDateTime.now().plus(1, ChronoUnit.DAYS)
        every { arkLevelService.findByLevelIdFuzzy(any()) } returns arkLevel
        doNothing().`when`(database).batchUpdate(Copilots) { any<Iterable<Copilot>>() }
        every { redisCache.removeCache(any<String>()) } returns Unit
        every { redisCache.syncRemoveCacheByPattern(any()) } returns Unit
        refreshTask.refreshTop100HotScores()

        check(copilot1.hotScore > 0)
        check(copilot2.hotScore > 0)
    }
}
