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
        // setCacheNames 是「只增不删」但不区分已存在项：传整个名单会把现有缓存对象全部重建。
        // 这里锁住「只传缺失的那一个名字」这一实现细节——否则将来有人顺手改成传全量名单，
        // 就会在启动时把已注册的自定义缓存（registerCustomCache）覆盖掉。
        val manager = staticManager("arkLevel", name)
        val before = manager.getCache("arkLevel")

        customizer().customize(manager)

        assertSame(before, manager.getCache("arkLevel"), "已存在的缓存对象不应被重建")
        assertNotNull(manager.getCache(name))
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
