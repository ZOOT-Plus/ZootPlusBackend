package plus.maa.backend.service.level

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LevelCachePolicy] 的判据测试：`v` 与查询串形态 → `Cache-Control`。
 *
 * 这一层是「内容寻址 URL + 长缓存」成立的前提，两条约束各自对应一类真实故障：
 * - `v` 不校验格式 → 外部可用任意字符串制造无限多的 cache key（缓存键稀释）；
 * - 查询串不校验规范形态 → 同一份内容被写成多个 key，WAF 回源次数被放大。
 */
class LevelCachePolicyTest {

    private val version = "0dffa3500000000000000000000000ab"

    private fun control(v: String? = version, query: String? = "v=$version", lite: Boolean = false, withSize: Boolean = false) =
        LevelCachePolicy.cacheControl(v, version, query, lite, withSize)

    @Test
    fun canonicalQueryOmitsFalseSwitches() {
        assertEquals("v=$version", LevelCachePolicy.canonicalQuery(version, lite = false, withSize = false))
        assertEquals("v=$version&lite=true", LevelCachePolicy.canonicalQuery(version, lite = true, withSize = false))
        assertEquals("v=$version&withSize=true", LevelCachePolicy.canonicalQuery(version, lite = false, withSize = true))
        assertEquals(
            "v=$version&lite=true&withSize=true",
            LevelCachePolicy.canonicalQuery(version, lite = true, withSize = true),
            "顺序固定为 v → lite → withSize",
        )
    }

    @Test
    fun canonicalQueryIsImmutable() {
        val current = control(query = "v=$version")
        assertTrue(current == LevelCachePolicy.IMMUTABLE, "实际：$current")
    }

    @Test
    fun malformedOrUnknownVersionFallsBackToShortCache() {
        // 用列表而非 @ValueSource：注解参数必须是编译期常量，而下面几条要用 version 变量拼接
        // （kapt 生成 stub 时无法求值这种模板，会直接编译失败）
        val malformed = listOf(
            "", // 缺失
            "00000000000000000000000000000000", // 不等
            "0dffa3500000000000000000000000a", // 少一位
            "0dffa3500000000000000000000000abb", // 多一位
            "0dffa3500000000000000000000000ag", // 非 hex 字符
            "0DFFA3500000000000000000000000AB", // 大写 hex：同摘要的两种写法不应各占一个缓存键
            "$version-extra", // 前缀注入：正则必须锚定全长
            "x$version",
        )

        malformed.forEach { v ->
            assertEquals(
                LevelCachePolicy.SHORT,
                control(v = v, query = "v=$v"),
                "v=$v 不应进入 immutable 通道",
            )
            assertFalse(LevelCachePolicy.isImmutableHit(v, version, "v=$v", lite = false, withSize = false))
        }
    }

    @Test
    fun missingVersionFallsBackToShortCache() {
        assertEquals(LevelCachePolicy.SHORT, control(v = null, query = null))
        assertEquals(LevelCachePolicy.SHORT, control(v = null, query = ""))
        assertFalse(LevelCachePolicy.isImmutableHit(null, version, null, lite = false, withSize = false))
    }

    @Test
    fun equivalentButNonCanonicalWritingsGetShortCache() {
        // 这些写法都能返回正确内容，但每个都是 WAF 与浏览器里的独立缓存条目 —— 只给短缓存，
        // 使其无法把长缓存空间撑大。内容正确性不受影响（服务端语义对参数顺序不敏感）。
        val nonCanonical = listOf(
            "lite=$version", // 参数名写错，服务端会当作 v 缺失 → 内容也不对，但这条只锁缓存策略
            "v=$version&lite=false", // 显式 false 是另一个 key
            "v=$version&lite=1", // 非规范布尔写法
            "lite=true&v=$version", // 顺序反了（WAF 不排序）
            "v=$version&lite=true&withSize=false",
            "v=$version&withSize=true&lite=true", // 顺序反了
            "v=$version&", // 尾随分隔符
            "v=$version%20", // 尾随空白
        )

        nonCanonical.forEach { query ->
            val lite = query.contains("lite=true")
            val withSize = query.contains("withSize=true")
            assertEquals(
                LevelCachePolicy.SHORT,
                LevelCachePolicy.cacheControl(version, version, query, lite, withSize),
                "非规范写法不应进入 immutable 通道：$query",
            )
        }
    }

    @Test
    fun canonicalWritingsRemainImmutable() {
        // 反向断言：收紧规范形态的同时不能把四个正常写法一起挡掉
        listOf(
            Triple("v=$version", false, false),
            Triple("v=$version&lite=true", true, false),
            Triple("v=$version&withSize=true", false, true),
            Triple("v=$version&lite=true&withSize=true", true, true),
        ).forEach { (query, lite, withSize) ->
            assertEquals(
                LevelCachePolicy.IMMUTABLE,
                LevelCachePolicy.cacheControl(version, version, query, lite, withSize),
                "规范写法应进入 immutable 通道：$query",
            )
        }
    }

    @Test
    fun unknownVersionStillReturnsShortCacheNotImmutable() {
        // 版本落后时的自愈路径：返回当前数据 + 短缓存，客户端更新本地版本号后即进 immutable 通道。
        // 若这里误给 immutable，客户端会把「陈旧 URL 配新内容」永久缓存下来。
        assertEquals(LevelCachePolicy.SHORT, control(v = "a".repeat(32), query = "v=${"a".repeat(32)}"))
    }
}
