package plus.maa.backend.controller

import com.github.benmanes.caffeine.cache.stats.CacheStats
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.delay
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.springframework.boot.info.GitProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.controller.SystemController.CacheStatInfo.Companion.covert
import plus.maa.backend.controller.response.MaaResult
import java.time.Instant
import kotlin.math.roundToLong
import plus.maa.backend.cache.InternalComposeCache as Cache

/**
 * @author AnselYuki
 */
@Tag(name = "System", description = "系统管理接口")
@RequestMapping("")
@RestController
class SystemController(
    private val properties: MaaCopilotProperties,
    private val gitProperties: GitProperties,
) {
    /**
     * Tests if the server is ready.
     * @return 系统启动信息
     */
    @GetMapping("/")
    suspend fun test(): MaaResult<Nothing> {
        delay(1000L)
        return MaaResult.success("Maa Copilot Server is Running", null)
    }

    /**
     * Gets the current version of the server.
     * @return 系统版本信息
     */
    @GetMapping("version")
    fun getSystemVersion(): MaaResult<MaaSystemInfo> {
        val info = properties.info
        val systemInfo = MaaSystemInfo(info.title, info.description, info.version, gitProperties)
        return MaaResult.success(systemInfo)
    }

    /**
     * 返回进程内缓存统计信息
     * @return [MaaResult<Map<String, CacheStatInfo>>]
     */
    @GetMapping("/cache/stat")
    fun getIntraProcessCacheStat(): MaaResult<Map<String, CacheStatInfo>> {
        val ret = mapOf(
            "copilot" to Cache.getCopilotCacheStat().covert(),
            "user" to Cache.getMaaUserCacheStat().covert(),
            "commentCount" to Cache.getCommentCountStat().covert(),
        )
        return MaaResult.success(ret)
    }

    @Serializable
    data class CacheStatInfo(
        val hitRate: Double,
        val missRate: Double,
        val hitCount: Long,
        val missCount: Long,
        val loadCount: Long,
        val evictionCount: Long,
        // 平均加载时间ms
        val averageLoadPenalty: Long,
    ) {
        companion object {
            fun CacheStats.covert(): CacheStatInfo {
                return CacheStatInfo(
                    hitRate(),
                    missRate(),
                    hitCount(),
                    missCount(),
                    loadCount(),
                    evictionCount(),
                    (averageLoadPenalty() / 1000L).roundToLong(),
                )
            }
        }
    }

    @Serializable
    data class MaaSystemInfo(
        val title: String,
        val description: String,
        val version: String,
        val git: GitInfo,
    ) {
        constructor(title: String, description: String, version: String, gitProperties: GitProperties) : this(
            title,
            description,
            version,
            GitInfo(
                branch = gitProperties.branch ?: "unknown",
                commitId = gitProperties.commitId ?: "unknown",
                shortCommitId = gitProperties.shortCommitId ?: "unknown",
                commitTime = gitProperties.commitTime ?: Instant.now(),
            ),
        )

        @Serializable
        data class GitInfo(
            val branch: String,
            val commitId: String,
            val shortCommitId: String,
            @Contextual
            val commitTime: Instant,
        )
    }
}
