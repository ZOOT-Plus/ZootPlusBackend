package plus.maa.backend.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

/**
 * [RatingService] 集成测试（不依赖 Spring 上下文，直接构造 repository/service）。
 *
 * 重点覆盖 [RatingService.rate] 的并发首次评分竞态：
 * - 基线 find-then-insert 在并发未命中时会撞 idx_rating_unique 抛 DuplicateKeyException（未捕获 → 500）。
 * - 修复后 miss 路径走 [RatingRepository.insertOrGet]（INSERT ... ON CONFLICT DO NOTHING + 重读），
 *   后到者不再抛唯一约束异常，而是拿到已存在行并正常更新。
 */
class RatingServiceTest : TestDbSupport() {

    private val ratingRepository = RatingRepository(jdbi)
    private val service = RatingService(ratingRepository)

    @Test
    fun rateFirstTimeInsertsNoneThenUpdatesToRequested() {
        val (prev, next) = service.rate(Rating.KeyType.COPILOT, "copilot-1", "user-1", RatingType.LIKE)

        assertEquals(RatingType.NONE, prev.rating, "首次评分的 prev 应为 NONE 占位")
        assertEquals(RatingType.LIKE, next.rating)
        assertTrue(next.id > 0)
        assertEquals(1L, ratingRepository.count(), "首次评分最终只应有 1 行")

        val reloaded = ratingRepository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-1", "user-1")!!
        assertEquals(RatingType.LIKE, reloaded.rating)
    }

    @Test
    fun rateSameValueIsNoOp() {
        service.rate(Rating.KeyType.COPILOT, "copilot-1", "user-1", RatingType.LIKE)

        val (prev, next) = service.rate(Rating.KeyType.COPILOT, "copilot-1", "user-1", RatingType.LIKE)

        assertEquals(prev.rating, next.rating)
        assertEquals(prev.id, next.id)
        assertEquals(1L, ratingRepository.count(), "同值评分不应新增行")
    }

    @Test
    fun rateChangeUpdatesExistingRow() {
        service.rate(Rating.KeyType.COPILOT, "copilot-1", "user-1", RatingType.LIKE)

        val (prev, next) = service.rate(Rating.KeyType.COPILOT, "copilot-1", "user-1", RatingType.DISLIKE)

        assertEquals(RatingType.LIKE, prev.rating, "prev 应为变更前的评级")
        assertEquals(RatingType.DISLIKE, next.rating)
        assertEquals(1L, ratingRepository.count(), "变更评级不应新增行")
        assertEquals(
            RatingType.DISLIKE,
            ratingRepository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-1", "user-1")!!.rating,
        )
    }

    @Test
    fun rateCommentAndCopilotUseDistinctTriples() {
        service.rateComment(100L, "user-1", RatingType.LIKE)
        service.rateCopilot(100L, "user-1", RatingType.DISLIKE)

        // key 同为 "100" 但 type 不同，应各自成行
        assertEquals(2L, ratingRepository.count())
        val comment = ratingRepository.findByTypeAndKeyAndUserId(Rating.KeyType.COMMENT, "100", "user-1")!!
        val copilot = ratingRepository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "100", "user-1")!!
        assertEquals(RatingType.LIKE, comment.rating)
        assertEquals(RatingType.DISLIKE, copilot.rating)
        assertNotEquals(comment.id, copilot.id)
    }

    @Test
    fun findPersonalRatingOfCopilotReturnsNullWhenAbsent() {
        assertNull(service.findPersonalRatingOfCopilot("user-1", 100L))

        service.rateCopilot(100L, "user-1", RatingType.LIKE)

        assertEquals(RatingType.LIKE, service.findPersonalRatingOfCopilot("user-1", 100L)!!.rating)
    }

    @Test
    fun rateSurvivesConcurrentFirstTimeInsertRace() {
        // 模拟并发竞态的“后到者”：在调用 rate 前，另一请求已抢先对同一三元组插入 NONE 占位行
        // （等价于两个并发请求都未命中 find 后，其中一个先 insert 成功）。
        // 旧实现：后到者 insertEntity 撞 idx_rating_unique → DuplicateKeyException（未捕获 → 500）。
        // 新实现：insertOrGet 用 ON CONFLICT DO NOTHING + 重读，后到者拿到已存在行并正常更新。
        val preexisting = RatingEntity(
            type = Rating.KeyType.COPILOT,
            key = "copilot-1",
            userId = "user-1",
            rating = RatingType.NONE,
            rateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0),
        )
        ratingRepository.insertEntity(preexisting)

        val (prev, next) = service.rate(Rating.KeyType.COPILOT, "copilot-1", "user-1", RatingType.LIKE)

        assertEquals(RatingType.NONE, prev.rating, "应识别出已存在的 NONE 占位行作为 prev")
        assertEquals(RatingType.LIKE, next.rating)
        assertEquals(preexisting.id, next.id, "应复用已存在行而非新建")
        assertEquals(1L, ratingRepository.count(), "竞态下不应产生重复行")
        assertEquals(
            RatingType.LIKE,
            ratingRepository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, "copilot-1", "user-1")!!.rating,
        )
    }
}
