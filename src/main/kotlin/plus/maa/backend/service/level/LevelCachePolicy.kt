package plus.maa.backend.service.level

/**
 * `/arknights/level/v2` 的缓存策略：由请求的 `v` 与查询串形态决定 `Cache-Control`。
 *
 * 独立成对象而非控制器里的私有方法，是为了能脱离 Spring 上下文直接单测——本仓库的
 * `@SpringBootTest` 依赖 gitignore 掉的 `application-dev.yml`，在本机与 CI 上都不可用。
 */
object LevelCachePolicy {

    /**
     * `v` 的合法形态（32 位小写 hex md5）。非法值一律按缺失处理。
     *
     * 校验是必需的：否则任意字符串都能配着 `public` 缓存头写进浏览器与 WAF 缓存，
     * 等于给外部一个缓存键稀释器。
     */
    private val VERSION_PATTERN = Regex("^[0-9a-f]{32}$")

    /** 版本号命中：该 URL 对应唯一内容（内容寻址），今后不必再回源。 */
    const val IMMUTABLE = "public, max-age=31536000, immutable"

    /**
     * 版本号缺失/不等/非法/写法不规范：内容会漂移到 current，只能短缓存。
     *
     * 60 秒的取舍：这段时间内同一 WAF 节点的所有客户端共享一份缓存（省回源），同时保证客户端在
     * 版本号变化后最迟 1 分钟就能拿到新内容。
     */
    const val SHORT = "public, max-age=60"

    /**
     * 规范查询串：`v` 在前，紧随其后的开关只在为 true 时出现，顺序固定。
     *
     * 顺序与写法必须固定，因为 WAF 把查询串计入缓存键且**不做归一化**（实测 `?a=1&b=2` 与
     * `?b=2&a=1` 是两个独立条目）。放任变体写法会让同一份内容在浏览器与 WAF 里各占多份缓存。
     */
    fun canonicalQuery(v: String, lite: Boolean, withSize: Boolean): String = buildString {
        append("v=").append(v)
        if (lite) append("&lite=true")
        if (withSize) append("&withSize=true")
    }

    /**
     * 是否可以按 immutable 缓存：`v` 合法、等于当前版本，且查询串恰是规范写法。
     *
     * 最后一条是防「缓存键稀释」的兜底：`?lite=1`、`?v=X&lite=true`、`?lite=false` 等写法都能返回
     * 正确内容，但每个写法都是 WAF 与浏览器里的一个独立缓存条目。对它们只给短缓存，使非规范写法
     * 无法把长缓存空间撑大。
     */
    fun isImmutableHit(v: String?, currentVersion: String, rawQueryString: String?, lite: Boolean, withSize: Boolean): Boolean =
        v != null && VERSION_PATTERN.matches(v) && v == currentVersion &&
            rawQueryString == canonicalQuery(v, lite, withSize)

    /** 按上述判据给出该请求的 `Cache-Control`。 */
    fun cacheControl(v: String?, currentVersion: String, rawQueryString: String?, lite: Boolean, withSize: Boolean): String =
        if (isImmutableHit(v, currentVersion, rawQueryString, lite, withSize)) IMMUTABLE else SHORT
}
