package plus.maa.backend.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.cache.caffeine.CaffeineCacheManager

/**
 * [CacheConfig.arkLevelV2CacheCustomizer] 的行为测试。
 *
 * 防的故障是「新端点依赖的缓存名没被写进外部 `spring.cache.cache-names` → 运行时 500」。
 * 这类漂移在仓库内测不出来（覆盖它的配置在部署机上），所以要把兜底逻辑本身锁住：
 * 该补的要补上，不该动的（现有缓存对象、动态模式）一个字都不能动。
 */
class CacheConfigTest {

    private val name = CacheConfig.ARK_LEVEL_SNAPSHOTS_V2

    private fun customizer() = CacheConfig().arkLevelV2CacheCustomizer()

    private fun staticManager(vararg names: String): CaffeineCacheManager = CaffeineCacheManager().apply {
        setCaffeine(Caffeine.newBuilder())
        setCacheNames(names.toList())
    }

    @Test
    fun registersNameWhenStaticListLacksIt() {
        val manager = staticManager("arkLevel", "arkLevelInfos", "copilotPage")

        customizer().customize(manager)

        assertNotNull(manager.getCache(name), "新缓存名必须可用（否则端点 500）")
        assertTrue(
            manager.cacheNames!!.containsAll(listOf("arkLevel", "arkLevelInfos", "copilotPage")),
            "原有缓存名不应丢失，实际 ${manager.cacheNames}",
        )
    }

    @Test
    fun doesNotTouchExistingCachesWhenAppending() {
        // 针对「setCacheNames 会替换整个名单、把既有缓存全部摘除」这一误判的正面证据：
        // Spring 的 javadoc 明确「replaces existing caches of the given names ... but does not remove
        // unrelated existing caches」，实现也只是逐个 put + 置 dynamic=false，不清空 cacheMap。
        // 这里对意见点名的三个缓存名逐个断言**对象未被重建**（assertSame，而非只看名字还在）
        // ——对象被重建的话，运行中的缓存条目会静默丢失，是比「名字消失」更隐蔽的故障。
        val existing = listOf("arkLevel", "arkLevelInfos", "copilotPage")
        val manager = staticManager(*existing.toTypedArray())
        val before = existing.associateWith { manager.getCache(it) }

        customizer().customize(manager)

        existing.forEach { cacheName ->
            assertSame(before[cacheName], manager.getCache(cacheName), "既有缓存 $cacheName 的对象不应被重建")
        }
        assertNotNull(manager.getCache(name), "新缓存名应已补上")
        assertTrue(
            manager.cacheNames!!.containsAll(existing + name),
            "补登记后名单应同时含既有名字与新名字，实际 ${manager.cacheNames}",
        )
    }

    @Test
    fun leavesDynamicModeAlone() {
        // 未配置 cache-names：动态模式下 getCache 自己就会创建，无需干预，
        // 也不得调用 setCacheNames —— 那会把 dynamic 置为 false，使其它缓存的动态创建全部失效
        val manager = CaffeineCacheManager().apply { setCaffeine(Caffeine.newBuilder()) }

        customizer().customize(manager)

        assertNotNull(manager.getCache(name))
        assertNotNull(manager.getCache("someOtherNameNotConfiguredAnywhere"))
    }

    @Test
    fun staticManagerStillRefusesUnregisteredNames() {
        // 反向说明「静态名单」的破坏力，也就是本兜底存在的理由
        val manager = staticManager("arkLevel")

        assertNull(manager.getCache("notRegistered"), "静态名单下未登记的名字取不到缓存")

        customizer().customize(manager)
        assertNotNull(manager.getCache(name))
        assertNull(manager.getCache("notRegistered"), "兜底只补自己的名字，不改变静态模式的语义")
    }
}
