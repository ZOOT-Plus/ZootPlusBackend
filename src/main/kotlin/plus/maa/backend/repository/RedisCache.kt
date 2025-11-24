package plus.maa.backend.repository

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.lang3.StringUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import plus.maa.backend.common.serialization.defaultJson
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@RedisCache.RedisCacheInternalApi
val log = KotlinLogging.logger { }

/**
 * Redis工具类
 *
 * @author AnselYuki
 */
@Component
@OptIn(RedisCache.RedisCacheInternalApi::class)
class RedisCache(
    @Value($$"${maa-copilot.cache.default-expire}") expire: Int,
    @RedisCacheInternalApi
    val redisTemplate: StringRedisTemplate,
) {
    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
    @RequiresOptIn("only public for technical reason, do not use this directly", RequiresOptIn.Level.ERROR)
    annotation class RedisCacheInternalApi

    @RedisCacheInternalApi
    final val json: Json = defaultJson

    @RedisCacheInternalApi
    final val expire = expire.seconds


    private val supportUnlink = AtomicBoolean(true)

    /*
        使用 lua 脚本插入数据，维持 ZSet 的相对大小（size <= 实际大小 <= size + 50）以及过期时间
        实际大小这么设计是为了避免频繁的 ZREMRANGEBYRANK 操作
     */
    @RedisCacheInternalApi
    val incZSetRedisScript: RedisScript<Any> = RedisScript.of(ClassPathResource("redis-lua/incZSet.lua"))

    // 比较与输入的键值对是否相同，相同则删除
    @RedisCacheInternalApi
    val removeKVIfEqualsScript: RedisScript<Boolean?> = RedisScript.of(
        ClassPathResource("redis-lua/removeKVIfEquals.lua"),
        Boolean::class.java,
    )

    final inline fun <reified T : Any> setData(key: String, value: T) {
        setCache(key, getJson(value) ?: return, 0.seconds)
    }

    final inline fun <reified T> setCache(key: String, value: T, timeout: Duration = expire) {
        val encoded = getJson(value) ?: return
        setCacheString(key, encoded, timeout)
    }

    @RedisCacheInternalApi
    fun setCacheString(key: String, encoded: String, timeout: Duration = expire) {
        if (!timeout.isPositive()) {
            redisTemplate.opsForValue()[key] = encoded
        } else {
            redisTemplate.opsForValue().set(key, encoded, timeout.toJavaDuration())
        }
    }


    /**
     * 当缓存不存在时，则 set
     *
     * @param key 缓存的 key
     * @param value 被缓存的值
     * @param timeout 过期时间
     * @return 是否 set
     */
    final inline fun <reified T> setCacheIfAbsent(key: String, value: T, timeout: Duration): Boolean {
        val encoded = getJson(value) ?: return false
        return setCacheStringIfAbsent(key, encoded, timeout)
    }


    @RedisCacheInternalApi
    fun setCacheStringIfAbsent(key: String, jsonString: String, timeout: Duration): Boolean {
        val result = if (!timeout.isPositive()) {
            redisTemplate.opsForValue().setIfAbsent(key, jsonString) == true
        } else {
            redisTemplate.opsForValue().setIfAbsent(key, jsonString, timeout.toJavaDuration()) == false
        }
        return result
    }

    final inline fun <reified T> addSet(key: String, set: Collection<T>, timeout: Duration) {
        if (set.isEmpty()) {
            return
        }
        val jsonList = arrayOfNulls<String>(set.size)
        for ((i, t) in set.withIndex()) {
            val json = getJson(t) ?: return
            jsonList[i] = json
        }
        return addSetJsonStrings(key, jsonList, timeout)
    }

    @RedisCacheInternalApi
    fun addSetJsonStrings(key: String, jsonList: Array<String?>, timeout: Duration) {
        if (!timeout.isPositive()) {
            redisTemplate.opsForSet().add(key, *jsonList)
        } else {
            redisTemplate.opsForSet().add(key, *jsonList)
            redisTemplate.expire(key, timeout.toJavaDuration())
        }
    }

    /**
     * ZSet 中元素的 score += incScore，如果元素不存在则插入 <br></br>
     * 会维持 ZSet 的相对大小（size <= 实际大小 <= size + 50）以及过期时间 <br></br>
     * 当大小超出 size + 50 时，会优先删除 score 最小的元素，直到大小等于 size
     *
     * @param key      ZSet 的 key
     * @param member   ZSet 的 member
     * @param incScore 增加的 score
     * @param size     ZSet 的相对大小
     * @param timeout  ZSet 的过期时间
     */
    fun incZSet(key: String, member: String?, incScore: Double, size: Long, timeout: Long) {
        redisTemplate.execute(
            incZSetRedisScript,
            listOf(key),
            member,
            incScore.toString(),
            size.toString(),
            timeout.toString(),
        )
    }

    // 获取的元素是按照 score 从小到大排列的
    fun getZSet(key: String, start: Long, end: Long): Set<String>? {
        return redisTemplate.opsForZSet().range(key, start, end)
    }

    // 获取的元素是按照 score 从大到小排列的
    fun getZSetReverse(key: String, start: Long, end: Long): Set<String>? {
        return redisTemplate.opsForZSet().reverseRange(key, start, end)
    }

    final inline fun <reified T> valueMemberInSet(key: String, value: T): Boolean {
        val json = getJson(value) ?: return false
        return jsonStringMemberInSet(key, json)
    }

    @RedisCacheInternalApi
    fun jsonStringMemberInSet(key: String, jsonString: String): Boolean {
        return try {
            redisTemplate.opsForSet().isMember(key, jsonString) == true
        } catch (e: Exception) {
            log.error(e) { e.message }
            false
        }
    }

    final inline fun <reified T> getCache(key: String, noinline onMiss: (() -> T)? = null, timeout: Duration = expire): T? {
        try {
            var cached = redisTemplate.opsForValue()[key]
            if (cached.isNullOrEmpty()) {
                if (onMiss == null) {
                    return null
                }
                // 上锁
                synchronized(RedisCache::class.java) {
                    // 再次查询缓存，目的是判断是否前面的线程已经set过了
                    cached = redisTemplate.opsForValue()[key]
                    // 第二次校验缓存是否存在
                    if (cached.isNullOrEmpty()) {
                        val result = onMiss()
                        // 数据库中不存在
                        setCache(key, result, timeout)
                        return result
                    }
                }
            }
            return cached?.let { json.decodeFromString<T>(it) }
        } catch (e: Exception) {
            log.error(e) { e.message }
            return null
        }
    }

    final inline fun <reified T> updateCache(key: String, defaultValue: T, onUpdate: (T) -> T, timeout: Duration = expire) {
        var result: T
        try {
            synchronized(RedisCache::class.java) {
                val cached = redisTemplate.opsForValue()[key]
                result = if (cached.isNullOrEmpty()) {
                    defaultValue
                } else {
                    json.decodeFromString(cached)
                }
                result = onUpdate(result)
                setCache(key, result, timeout)
            }
        } catch (e: Exception) {
            log.error(e) { e.message }
        }
    }

    @JvmOverloads
    fun removeCache(key: String, notUseUnlink: Boolean = false) {
        removeCache(listOf(key), notUseUnlink)
    }

    @JvmOverloads
    fun removeCache(keys: Collection<String>, notUseUnlink: Boolean = false) {
        if (!notUseUnlink && supportUnlink.get()) {
            val exceptionHandler = { e: Exception ->
                val cause = e.cause
                if (cause == null || !StringUtils.containsAny(
                        cause.message,
                        "unknown command",
                        "not support",
                    )
                ) {
                    throw e
                }
                if (supportUnlink.compareAndSet(true, false)) {
                    log.warn { "当前连接的 Redis Service 可能不支持 Unlink 命令，切换为 Del" }
                }
            }
            try {
                redisTemplate.unlink(keys)
                return
            } catch (e: InvalidDataAccessApiUsageException) {
                // Redisson、Jedis、Lettuce
                exceptionHandler(e)
            } catch (e: RedisSystemException) {
                exceptionHandler(e)
            }
        }

        // 兜底的 Del 命令
        redisTemplate.delete(keys)
    }

    /**
     * 相同则删除键值对
     *
     * @param key 待比较和删除的键
     * @param value 待比较的值
     * @return 是否删除
     */
    final inline fun <reified T> removeKVIfEquals(key: String, value: T): Boolean {
        val json = getJson(value) ?: return false
        @Suppress("SimplifyBooleanWithConstants")
        return redisTemplate.execute(removeKVIfEqualsScript, listOf(key), json) == true
    }

    /**
     * 模糊删除缓存。不保证立即删除，不保证完全删除。<br></br>
     * 异步，因为 Scan 虽然不会阻塞 Redis，但客户端会阻塞
     *
     * @param pattern 待删除的 Key 表达式，例如 "home:*" 表示删除 Key 以 "home:" 开头的所有缓存
     * @author Lixuhuilll
     */
    @Async
    fun removeCacheByPattern(pattern: String) {
        syncRemoveCacheByPattern(pattern)
    }

    /**
     * 模糊删除缓存。不保证立即删除，不保证完全删除。<br></br>
     * 同步调用 Scan，不会长时间阻塞 Redis，但会阻塞客户端，阻塞时间视 Redis 中 key 的数量而定。
     * 删除期间，其他线程或客户端可对 Redis 进行 CURD（因为不阻塞 Redis），因此不保证删除的时机，也不保证完全删除干净
     *
     * @param pattern 待删除的 Key 表达式，例如 "home:*" 表示删除 Key 以 "home:" 开头的所有缓存
     * @author Lixuhuilll
     */
    fun syncRemoveCacheByPattern(pattern: String) {
        // 批量删除的阈值
        val batchSize = 2000
        // 构造 ScanOptions
        val scanOptions = ScanOptions.scanOptions()
            .count(batchSize.toLong())
            .match(pattern)
            .build()

        // 保存要删除的键
        val keysToDelete: MutableList<String> = ArrayList(batchSize)

        redisTemplate.scan(scanOptions).use { cursor ->
            while (cursor.hasNext()) {
                val key = cursor.next()
                // 将要删除的键添加到列表中
                keysToDelete.add(key)

                // 如果达到批量删除的阈值，则执行批量删除
                if (keysToDelete.size >= batchSize) {
                    removeCache(keysToDelete)
                    keysToDelete.clear()
                }
            }
        }
        // 删除剩余的键（不足 batchSize 的最后一批）
        if (keysToDelete.isNotEmpty()) {
            removeCache(keysToDelete)
        }
    }

    @RedisCacheInternalApi
    final inline fun <reified T> getJson(value: T): String? =
        try {
            json.encodeToString(value)
        } catch (e: SerializationException) {
            log.debug { e.message }
            null
        } catch (e: IllegalArgumentException) {
            log.debug { e.message }
            null
        }
}
