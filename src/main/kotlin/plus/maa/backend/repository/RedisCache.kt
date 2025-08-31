package plus.maa.backend.repository

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger { }

/**
 * Redis工具类
 *
 * @author AnselYuki
 */
@Component
class RedisCache(
    @param:Value($$"${maa-copilot.cache.default-expire}") private val expire: Int,
    private val redisTemplate: StringRedisTemplate,
) {
    //  添加 JSR310 模块，以便顺利序列化 LocalDateTime 等类型
    private val writeMapper: ObjectMapper = jacksonObjectMapper()
        .registerModules(JavaTimeModule())
    private val readMapper: ObjectMapper = jacksonObjectMapper()
        .registerModules(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val supportUnlink = AtomicBoolean(true)

    /*
        使用 lua 脚本插入数据，维持 ZSet 的相对大小（size <= 实际大小 <= size + 50）以及过期时间
        实际大小这么设计是为了避免频繁的 ZREMRANGEBYRANK 操作
     */
    private val incZSetRedisScript: RedisScript<Any> = RedisScript.of(ClassPathResource("redis-lua/incZSet.lua"))

    // 比较与输入的键值对是否相同，相同则删除
    private val removeKVIfEqualsScript: RedisScript<Boolean> = RedisScript.of(
        ClassPathResource("redis-lua/removeKVIfEquals.lua"),
        Boolean::class.java,
    )

    fun <T> setData(key: String, value: T) {
        setCache(key, value, 0, TimeUnit.SECONDS)
    }

    fun <T> setCache(key: String, value: T) {
        setCache(key, value, expire.toLong(), TimeUnit.SECONDS)
    }

    fun <T> setCache(key: String, value: T, timeout: Long) {
        setCache(key, value, timeout, TimeUnit.SECONDS)
    }

    fun <T> setCache(key: String, value: T, timeout: Long, timeUnit: TimeUnit) {
        val json = getJson(value) ?: return
        if (timeout <= 0) {
            redisTemplate.opsForValue()[key] = json
        } else {
            redisTemplate.opsForValue()[key, json, timeout] = timeUnit
        }
    }

    /**
     * 当缓存不存在时，则 set
     *
     * @param key 缓存的 key
     * @param value 被缓存的值
     * @return 是否 set
     */
    fun <T> setCacheIfAbsent(key: String, value: T): Boolean {
        return setCacheIfAbsent(key, value, expire.toLong())
    }

    /**
     * 当缓存不存在时，则 set
     *
     * @param key 缓存的 key
     * @param value 被缓存的值
     * @param timeout 过期时间
     * @return 是否 set
     */
    fun <T> setCacheIfAbsent(key: String, value: T, timeout: Long): Boolean {
        return setCacheIfAbsent(key, value, timeout, TimeUnit.SECONDS)
    }

    /**
     * 当缓存不存在时，则 set
     *
     * @param key 缓存的 key
     * @param value 被缓存的值
     * @param timeout 过期时间
     * @param timeUnit 过期时间的单位
     * @return 是否 set
     */
    fun <T> setCacheIfAbsent(key: String, value: T, timeout: Long, timeUnit: TimeUnit): Boolean {
        val json = getJson(value) ?: return false
        val result = if (timeout <= 0) {
            java.lang.Boolean.TRUE == redisTemplate.opsForValue().setIfAbsent(key, json)
        } else {
            java.lang.Boolean.TRUE == redisTemplate.opsForValue().setIfAbsent(key, json, timeout, timeUnit)
        }
        return result
    }

    fun <T> addSet(key: String, set: Collection<T>, timeout: Long) {
        addSet(key, set, timeout, TimeUnit.SECONDS)
    }

    fun <T> addSet(key: String, set: Collection<T>, timeout: Long, timeUnit: TimeUnit) {
        if (set.isEmpty()) {
            return
        }
        val jsonList = arrayOfNulls<String>(set.size)
        for ((i, t) in set.withIndex()) {
            val json = getJson(t) ?: return
            jsonList[i] = json
        }

        if (timeout <= 0) {
            redisTemplate.opsForSet().add(key, *jsonList)
        } else {
            redisTemplate.opsForSet().add(key, *jsonList)
            redisTemplate.expire(key, timeout, timeUnit)
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

    fun <T> valueMemberInSet(key: String, value: T): Boolean {
        try {
            val json = getJson(value) ?: return false
            return java.lang.Boolean.TRUE == redisTemplate.opsForSet().isMember(key, json)
        } catch (e: Exception) {
            log.error(e) { e.message }
        }
        return false
    }

    fun <T> getCache(key: String, valueType: Class<T>): T? {
        return getCache(key, valueType, null, expire.toLong(), TimeUnit.SECONDS)
    }

    fun <T> getCache(key: String, valueType: Class<T>, onMiss: (() -> T)?): T? {
        return getCache(key, valueType, onMiss, expire.toLong(), TimeUnit.SECONDS)
    }

    fun <T> getCache(key: String, valueType: Class<T>, onMiss: (() -> T)?, timeout: Long): T? {
        return getCache(key, valueType, onMiss, timeout, TimeUnit.SECONDS)
    }

    fun <T> getCache(key: String, valueType: Class<T>, onMiss: (() -> T)?, timeout: Long, timeUnit: TimeUnit): T? {
        try {
            var json = redisTemplate.opsForValue()[key]
            if (StringUtils.isEmpty(json)) {
                if (onMiss == null) {
                    return null
                }
                // 上锁
                synchronized(RedisCache::class.java) {
                    // 再次查询缓存，目的是判断是否前面的线程已经set过了
                    json = redisTemplate.opsForValue()[key]
                    // 第二次校验缓存是否存在
                    if (StringUtils.isEmpty(json)) {
                        val result = onMiss()
                        // 数据库中不存在
                        setCache(key, result, timeout, timeUnit)
                        return result
                    }
                }
            }
            return readMapper.readValue(json, valueType)
        } catch (e: Exception) {
            log.error(e) { e.message }
            return null
        }
    }

    fun <T> updateCache(key: String, valueType: Class<T>, defaultValue: T, onUpdate: (T) -> T) {
        updateCache(key, valueType, defaultValue, onUpdate, expire.toLong(), TimeUnit.SECONDS)
    }

    fun <T> updateCache(key: String, valueType: Class<T>?, defaultValue: T, onUpdate: (T) -> T, timeout: Long) {
        updateCache(key, valueType, defaultValue, onUpdate, timeout, TimeUnit.SECONDS)
    }

    fun <T> updateCache(key: String, valueType: Class<T>?, defaultValue: T, onUpdate: (T) -> T, timeout: Long, timeUnit: TimeUnit) {
        var result: T
        try {
            synchronized(RedisCache::class.java) {
                val json = redisTemplate.opsForValue()[key]
                result = if (StringUtils.isEmpty(json)) {
                    defaultValue
                } else {
                    readMapper.readValue(json, valueType)
                }
                result = onUpdate(result)
                setCache(key, result, timeout, timeUnit)
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
            try {
                redisTemplate.unlink(keys)
                return
            } catch (e: InvalidDataAccessApiUsageException) {
                // Redisson、Jedis、Lettuce
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
            } catch (e: RedisSystemException) {
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
    fun <T> removeKVIfEquals(key: String, value: T): Boolean {
        val json = getJson(value) ?: return false
        return java.lang.Boolean.TRUE == redisTemplate.execute(removeKVIfEqualsScript, listOf(key), json)
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

    private fun <T> getJson(value: T): String? {
        val json: String
        try {
            json = writeMapper.writeValueAsString(value)
        } catch (e: JsonProcessingException) {
            if (log.isDebugEnabled()) {
                log.debug(e) { e.message }
            }
            return null
        }
        return json
    }
}
