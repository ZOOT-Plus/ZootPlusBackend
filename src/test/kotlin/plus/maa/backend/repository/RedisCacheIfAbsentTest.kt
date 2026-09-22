package plus.maa.backend.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [RedisCache.setCacheIfAbsent] 语义回归测试（回归 #242）。
 *
 * 契约：返回 true 表示 key 原先不存在、本次成功写入；false 表示 key 已存在、未写入。
 * 曾因带 TTL 分支误将 setIfAbsent 结果取反，导致验证码发送限流完全倒置
 * （首次/过期后必 403，限流窗口内重试反而放行），作业浏览量计数同理。
 *
 * setCacheIfAbsent 是 inline 函数无法直接 mock，只能替换其内部使用的 redisTemplate
 * （故需要 opt-in 到 RedisCache 的内部 API，与 ArkLevelNameRepairTest 同一手法）。
 */
@OptIn(RedisCache.RedisCacheInternalApi::class)
class RedisCacheIfAbsentTest {
    private val values = mockk<ValueOperations<String, String>>()
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val redisCache = RedisCache(60, redisTemplate)

    init {
        every { redisTemplate.opsForValue() } returns values
    }

    @Test
    fun `absent key with ttl returns true and writes`() {
        every { values.setIfAbsent(any<String>(), any<String>(), any<Duration>()) } returns true

        val result = redisCache.setCacheIfAbsent("HasBeenSentVCode:a@b.c", 60, 60.seconds)

        assertTrue(result, "key 不存在时应视为本次写入成功")
        verify(exactly = 1) { values.setIfAbsent(any<String>(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `existing key with ttl returns false and does not overwrite`() {
        every { values.setIfAbsent(any<String>(), any<String>(), any<Duration>()) } returns false

        val result = redisCache.setCacheIfAbsent("HasBeenSentVCode:a@b.c", 60, 60.seconds)

        assertFalse(result, "key 已存在时应视为未写入")
    }

    @Test
    fun `absent key without ttl returns true`() {
        every { values.setIfAbsent(any<String>(), any<String>()) } returns true

        val result = redisCache.setCacheIfAbsent("k", "v", 0.seconds)

        assertTrue(result)
        verify(exactly = 0) { values.setIfAbsent(any<String>(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `existing key without ttl returns false`() {
        every { values.setIfAbsent(any<String>(), any<String>()) } returns false

        val result = redisCache.setCacheIfAbsent("k", "v", 0.seconds)

        assertFalse(result)
    }
}
