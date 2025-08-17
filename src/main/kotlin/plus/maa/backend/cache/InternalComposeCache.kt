package plus.maa.backend.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.stats.CacheStats
import plus.maa.backend.cache.transfer.CopilotInnerCacheInfo
import plus.maa.backend.repository.entity.UserEntity
import java.time.Duration

object InternalComposeCache {
    // copilotId -> info
    private val copilotCache = Caffeine.newBuilder()
        .recordStats()
        .expireAfterAccess(Duration.ofDays(3))
        .maximumSize(5000)
        .build<Long, CopilotInnerCacheInfo?>()

    // copilotId -> info
    private val maaUserCache = Caffeine.newBuilder()
        .recordStats()
        .expireAfterAccess(Duration.ofDays(3))
        .build<String, UserEntity>()

    // copilotId -> count
    private val commentCountCache = Caffeine.newBuilder()
        .recordStats()
        .expireAfterAccess(Duration.ofDays(3))
        .build<Long, Long>()

    fun getCopilotCacheStat(): CacheStats {
        return copilotCache.stats()
    }

    fun getCopilotCache(cid: Long, f: (Long) -> CopilotInnerCacheInfo?): CopilotInnerCacheInfo? {
        return copilotCache.get(cid, f)
    }

    fun getMaaUserCache(userId: String, f: (String) -> UserEntity): UserEntity {
        return maaUserCache.get(userId, f)
    }

    fun getMaaUserCache(userId: String): UserEntity? {
        return maaUserCache.getIfPresent(userId)
    }

    fun getMaaUserCacheStat(): CacheStats {
        return maaUserCache.stats()
    }

    fun setUserCache(userId: String, info: UserEntity) {
        maaUserCache.put(userId, info)
    }

    fun getCommentCountCache(cid: Long, f: (Long) -> Long): Long {
        return commentCountCache.get(cid, f)
    }

    fun getCommentCountCache(cid: Long): Long? {
        return commentCountCache.getIfPresent(cid)
    }

    fun getCommentCountStat(): CacheStats {
        return commentCountCache.stats()
    }

    fun setCommentCountCache(cid: Long, count: Long) {
        commentCountCache.put(cid, count)
    }

    fun invalidateCopilotInfoByCid(cid: Long?) {
        cid?.let {
            copilotCache.invalidate(it)
        }
    }

    fun invalidateMaaUserById(id: String?) {
        id?.let {
            maaUserCache.invalidate(it)
        }
    }

    fun invalidateCommentCountById(cid: Long?) {
        cid?.let {
            commentCountCache.invalidate(cid)
        }
    }
}
