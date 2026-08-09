package plus.maa.backend.task

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.service.level.ArkLevelService
import java.time.LocalDateTime

/**
 * 热度刷入任务的防回归测试：refreshHotScores 曾存在 offset 双重叠加 bug，
 * 导致每隔一页的作业被跳过。
 *
 * 用真实 embedded PG + 真实 repository，mock 掉 ArkLevelService（关卡查询）与
 * RedisCache（缓存清理）：插入 2500 条作业（3 页），刷新后断言全部被更新，
 * 覆盖分页循环的跨页逻辑。
 */
class CopilotScoreRefreshTaskTest : TestDbSupport() {

    private val copilotRepo = CopilotRepository(jdbi)
    private val ratingRepo = RatingRepository(jdbi)
    private val arkLevelService = mockk<ArkLevelService> {
        every { findByLevelIdFuzzy(any()) } returns null
    }
    private val redisCache = mockk<RedisCache>(relaxed = true)

    private val task = CopilotScoreRefreshTask(copilotRepo, ratingRepo, arkLevelService, redisCache)

    @Test
    fun `refreshHotScores updates all pages without skipping`() {
        val total = 2500
        val now = LocalDateTime.now()
        jdbi.useHandle<Exception> { handle ->
            val batch = handle.prepareBatch(
                """
                INSERT INTO copilot (type, stage_name, uploader_id, views, rating_level, rating_ratio,
                                     like_count, dislike_count, hot_score, title, first_upload_time,
                                     upload_time, content, status, comment_status, "delete", notification)
                VALUES ('PRTS', :stageName, 1, 0, 0, 0, 0, 0, 0, :title, :now, :now, :content,
                        'PUBLIC', 'ENABLED', FALSE, FALSE)
                """.trimIndent(),
            )
            repeat(total) { i ->
                batch.bind("stageName", "stage_$i")
                    .bind("title", "title_$i")
                    .bind("content", "content_$i")
                    .bind("now", now)
                    .add()
            }
            batch.execute()
        }

        task.refreshHotScores()

        val updated = jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM copilot WHERE hot_score <> 0")
                .mapTo(Long::class.java)
                .one()
        }
        assertEquals(total.toLong(), updated, "每页作业都应被刷新，不允许跳页")
    }
}
