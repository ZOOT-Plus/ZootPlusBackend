package plus.maa.backend.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 保证 `arkLevelSnapshotsV2` 缓存在任何配置下都可用，且 TTL 独立于全局 `spring.cache` 配置，
 * 使新端点的正确性与长缓存设计不依赖外部配置是否同步更新。
 *
 * 背景：`spring.cache.cache-names` 一旦被显式列出，[CaffeineCacheManager] 就切到「静态名单」模式，
 * 不再动态创建缓存——此后访问未登记的名字，`getCache` 返回 null，Spring 的缓存切面抛
 * `Cannot find cache named '...'`，端点直接 500。而生产/开发环境的 `application-prod.yml`、
 * `application-dev.yml` 都是 gitignore 的外部文件（部署时挂载），它们会**整体覆盖**而非合并
 * `cache-names`；仓库里改了 `application.yml` 不等于线上生效，CI 也测不到——典型的「代码对、配置漂移就炸」。
 *
 * 判据用 `cacheNames`（cacheMap 的只读键视图，不物化缓存）而非「名单是否为空」（后者区分不了
 * 「动态模式」与「静态名单恰好为空」），也**不**用 `getCache`——动态模式下 `getCache` 会顺手创建
 * 一个默认 spec 的缓存，此后 [CaffeineCacheManager.registerCustomCache] 就再没机会换成独立 TTL：
 * - 名字未物化（静态名单缺它，或动态模式尚未访问）：直接注册独立 spec 的缓存；
 * - 名字已被外部配置物化：跳过，不重建对象（重建会丢运行中的缓存条目）——优雅降级为全局
 *   spec，数据新鲜度仍由写路径的事件失效保证（`ArkLevelsSyncedEvent`）。
 */
@Configuration
class CacheConfig {

    @Bean
    fun arkLevelV2CacheCustomizer() = CacheManagerCustomizer<CaffeineCacheManager> { cacheManager ->
        if (ARK_LEVEL_SNAPSHOTS_V2 !in cacheManager.cacheNames) {
            cacheManager.registerCustomCache(
                ARK_LEVEL_SNAPSHOTS_V2,
                Caffeine.newBuilder().expireAfterWrite(SNAPSHOT_CACHE_TTL).build(),
            )
        }
    }

    companion object {
        /** `/arknights/level/v2` 的快照缓存名（见 `ArkLevelV2Service.snapshot`）。 */
        const val ARK_LEVEL_SNAPSHOTS_V2 = "arkLevelSnapshotsV2"

        /**
         * 快照缓存的兜底 TTL。新鲜度由写路径主动失效保证（`ArkLevelsSyncedEvent` →
         * `ArkLevelV2Service.onArkLevelsChanged` 失效并立即重建），TTL 只在事件丢失或出现未发事件的
         * 写路径时兜底，故远长于全局业务缓存的 5 分钟——这正是该缓存不进 `spring.cache.caffeine.spec`
         * 的原因。
         */
        val SNAPSHOT_CACHE_TTL: Duration = Duration.ofHours(1)
    }
}
