package plus.maa.backend.config

import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 保证 `arkLevelSnapshotsV2` 缓存在任何配置下都可用，使新端点的正确性不依赖外部配置是否同步更新。
 *
 * 背景：`spring.cache.cache-names` 一旦被显式列出，[CaffeineCacheManager] 就切到「静态名单」模式，
 * 不再动态创建缓存——此后访问未登记的名字，`getCache` 返回 null，Spring 的缓存切面抛
 * `Cannot find cache named '...'`，端点直接 500。而生产/开发环境的 `application-prod.yml`、
 * `application-dev.yml` 都是 gitignore 的外部文件（部署时挂载），它们会**整体覆盖**而非合并
 * `cache-names`；仓库里改了 `application.yml` 不等于线上生效，CI 也测不到——典型的「代码对、配置漂移就炸」。
 *
 * 判据用 `getCache` 本身而非名单是否为空（后者区分不了「动态模式」与「静态名单恰好为空」）：
 * - 动态模式：`getCache` 会自动创建并返回，非 null ⇒ 无需干预，其它缓存的动态创建也不受影响；
 * - 静态名单且已登记：同样非 null ⇒ 无需干预；
 * - 静态名单且未登记：返回 null ⇒ 追加该名字。
 *
 * 追加走 [CaffeineCacheManager.setCacheNames]，它按当前实现是**只增不删**（逐个 put，不清空 map），
 * 因此只传缺失的那一个名字即可，不会重建现有缓存对象。
 */
@Configuration
class CacheConfig {

    @Bean
    fun arkLevelV2CacheCustomizer() = CacheManagerCustomizer<CaffeineCacheManager> { cacheManager ->
        if (cacheManager.getCache(ARK_LEVEL_SNAPSHOTS_V2) == null) {
            cacheManager.setCacheNames(listOf(ARK_LEVEL_SNAPSHOTS_V2))
        }
    }

    companion object {
        /** `/arknights/level/v2` 的快照缓存名（见 `ArkLevelV2Service.payload`）。 */
        const val ARK_LEVEL_SNAPSHOTS_V2 = "arkLevelSnapshotsV2"
    }
}
