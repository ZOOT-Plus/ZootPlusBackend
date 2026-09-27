package plus.maa.backend.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.cache.caffeine.CaffeineCacheManager
import java.time.Duration

/**
 * [CacheConfig.arkLevelV2CacheCustomizer] 的行为测试。
 *
 * 防的故障有二：「新端点依赖的缓存名没被写进外部 `spring.cache.cache-names` → 运行时 500」，
 * 以及「快照缓存被全局 5 分钟 spec 物化 → 写路径事件失效所支撑的长 TTL 设计被悄悄绕过」。
 * 这类漂移在仓库内测不出来（覆盖它的配置在部署机上），所以要把兜底逻辑本身锁住：
 * 该补的要补上（且带独立 TTL），不该动的（现有缓存对象、动态模式）一个字都不能动。
 */
class CacheConfigTest {

    private val name = CacheConfig.ARK_LEVEL_SNAPSHOTS_V2

    private fun customizer() = CacheConfig().arkLevelV2CacheCustomizer()

    private fun staticManager(vararg names: String): CaffeineCacheManager = CaffeineCacheManager().apply {
        setCaffeine(Caffeine.newBuilder())
        setCacheNames(names.toList())
    }

    /** 快照缓存当前的写后过期时长；null 表示没有 expireAfterWrite 策略（默认 spec 物化或永不过期）。 */
    private fun snapshotTtlOf(manager: CaffeineCacheManager): Duration? = (manager.getCache(name)!!.nativeCache as Cache<*, *>)
        .policy()
        .expireAfterWrite()
        .map { it.expiresAfter }
        .orElse(null)

    @Test
    fun registersCacheWithIndependentTtlWhenStaticListLacksIt() {
        val manager = staticManager("arkLevel", "arkLevelInfos", "copilotPage")

        customizer().customize(manager)

        assertNotNull(manager.getCache(name), "新缓存名必须可用（否则端点 500）")
        assertEquals(
            CacheConfig.SNAPSHOT_CACHE_TTL,
            snapshotTtlOf(manager),
            "TTL 须独立于全局 spec，否则写路径主动失效换不来长缓存",
        )
        assertTrue(
            manager.cacheNames!!.containsAll(listOf("arkLevel", "arkLevelInfos", "copilotPage")),
            "原有缓存名不应丢失，实际 ${manager.cacheNames}",
        )
    }

    @Test
    fun doesNotTouchExistingCachesWhenRegistering() {
        // 对既有缓存逐个断言**对象未被重建**（assertSame，而非只看名字还在）——对象被重建的话，
        // 运行中的缓存条目会静默丢失，是比「名字消失」更隐蔽的故障。
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
            "注册后名单应同时含既有名字与新名字，实际 ${manager.cacheNames}",
        )
    }

    @Test
    fun leavesDynamicModeAlone() {
        // 未配置 cache-names：动态模式下其它名字由 getCache 按需创建，不受影响；
        // 判据必须用 cacheNames 而非 getCache——后者在动态模式下会顺手创建默认 spec 的快照缓存，
        // registerCustomCache 就再没机会换成独立 TTL
        val manager = CaffeineCacheManager().apply { setCaffeine(Caffeine.newBuilder()) }

        customizer().customize(manager)

        assertEquals(CacheConfig.SNAPSHOT_CACHE_TTL, snapshotTtlOf(manager))
        assertNotNull(manager.getCache("someOtherNameNotConfiguredAnywhere"), "动态创建能力不得被关闭")
    }

    @Test
    fun degradesToDefaultSpecWhenNameAlreadyListed() {
        // 外部配置把该名字显式列进 cache-names：缓存已按默认 spec 物化，customizer 不得重建
        // （重建会丢运行中条目）——保留现状即可，写路径的事件失效仍保证新鲜度，只是 TTL 回到全局值
        val manager = staticManager("arkLevel", name)
        val preExisting = manager.getCache(name)

        customizer().customize(manager)

        assertSame(preExisting, manager.getCache(name), "已物化的缓存对象不得被重建")
        assertNull(snapshotTtlOf(manager), "默认 spec 无 expireAfterWrite，说明不是兜底注册的独立 TTL")
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
