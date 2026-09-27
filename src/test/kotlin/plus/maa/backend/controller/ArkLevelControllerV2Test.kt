package plus.maa.backend.controller

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.copilot.LevelVersions
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.level.ArkLevelV2Service
import plus.maa.backend.service.level.LevelCachePolicy
import plus.maa.backend.service.level.LevelSnapshot

/**
 * [ArkLevelController] 的 v2 端点测试。
 *
 * 直接构造控制器（依赖用 mockk），不加载 Spring 上下文——本仓库 `@SpringBootTest` 依赖 gitignore 掉的
 * `application-dev.yml`，在本机与 CI 上都必然失败。此处只验证端点自身的行为：变体转发、
 * `Cache-Control`/`ETag` 头、以及两个端点给出的版本号一致。
 *
 * 响应对象按控制器约定（同 `CopilotController`）在构造时注入，故每个用例先取一个新的 mock response。
 */
class ArkLevelControllerV2Test {

    private val v1Service = mockk<ArkLevelService>(relaxed = true)

    private val fullVersion = "11111111111111111111111111111111"
    private val liteVersion = "22222222222222222222222222222222"

    private fun row(levelId: String) = ArkLevelEntity(
        levelId = levelId,
        stageId = "st-1",
        catOne = "活动关卡",
        catTwo = "活动",
        catThree = "C-1",
        name = "关卡",
        width = 1,
        height = 1,
        updatedAt = null,
    )

    // 缓存键只含 lite：两个变体各一份快照，withSize 由 controller 在缓存之外投影
    private val v2Service = mockk<ArkLevelV2Service> {
        every { snapshot(false) } returns LevelSnapshot(fullVersion, listOf(row("full")))
        every { snapshot(true) } returns LevelSnapshot(liteVersion, listOf(row("lite")))
    }

    /**
     * 每次请求重新构造控制器：请求/响应对象是构造注入的，复用会读到上一次写的头。
     *
     * 请求对象必须显式给方法名（`MockHttpServletRequest()` 的 method 为 null）：
     * `ServletWebRequest.checkNotModified` 只在安全方法（GET/HEAD/OPTIONS/TRACE）上才写 ETag 并判 304，
     * method 为 null 时 ETag 不会被写出、命中条件请求还会被降级成 412。
     */
    private fun getRequest(rawQuery: String? = null) = MockHttpServletRequest("GET", "/arknights/level/v2").apply {
        queryString = rawQuery
    }

    private var response = MockHttpServletResponse()

    /** 发一次请求；`rawQuery` 为原始查询串（null 表示无查询串）。 */
    private fun call(
        v: String? = null,
        lite: Boolean = false,
        withSize: Boolean = false,
        rawQuery: String? = null,
        ifNoneMatch: String? = null,
    ): MockHttpServletResponse {
        response = MockHttpServletResponse()
        val request = getRequest(rawQuery).apply {
            ifNoneMatch?.let { addHeader(HttpHeaders.IF_NONE_MATCH, it) }
        }
        ArkLevelController(v1Service, v2Service, request, response).getLevelsV2(v, lite, withSize)
        return response
    }

    /** 发一次版本探测请求。 */
    private fun callVersion(): Pair<MockHttpServletResponse, MaaResult<LevelVersions>> {
        response = MockHttpServletResponse()
        val result = ArkLevelController(v1Service, v2Service, getRequest(), response).getLevelVersions()
        return response to result
    }

    @Test
    fun canonicalHitGetsImmutableAndWeakEtag() {
        val response = call(v = fullVersion, rawQuery = "v=$fullVersion")

        assertEquals(LevelCachePolicy.IMMUTABLE, response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("W/\"$fullVersion\"", response.getHeader(HttpHeaders.ETAG))
    }

    @Test
    fun eachVariantGetsItsOwnVersionAndCacheKeyForm() {
        val lite = call(v = liteVersion, lite = true, rawQuery = "v=$liteVersion&lite=true")
        val sized = call(v = fullVersion, withSize = true, rawQuery = "v=$fullVersion&withSize=true")

        assertEquals(LevelCachePolicy.IMMUTABLE, lite.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("W/\"$liteVersion\"", lite.getHeader(HttpHeaders.ETAG))
        assertEquals(LevelCachePolicy.IMMUTABLE, sized.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals(
            "W/\"$fullVersion\"",
            sized.getHeader(HttpHeaders.ETAG),
            "withSize 不改变行集合，应与 no-size 共用版本号",
        )
    }

    @Test
    fun nonCanonicalQueryStringsGetShortCacheEvenWhenTheVersionMatches() {
        // 非规范写法能返回正确内容，但每个写法都是 WAF/浏览器里的独立缓存条目 → 只给短缓存，
        // 使其无法把长缓存空间撑大
        listOf(
            "lite=$fullVersion" to false,
            "v=$fullVersion&lite=false" to false,
            "v=$fullVersion&lite=1" to true,
            "lite=true&v=$fullVersion" to true,
            "v=$fullVersion&withSize=true&lite=true" to true,
        ).forEach { (query, lite) ->
            val response = call(v = fullVersion, lite = lite, rawQuery = query)
            assertEquals(
                LevelCachePolicy.SHORT,
                response.getHeader(HttpHeaders.CACHE_CONTROL),
                "非规范查询串不应进入 immutable 通道：$query",
            )
        }
    }

    @Test
    fun staleOrMissingVersionGetsShortCache() {
        listOf(
            call(rawQuery = null),
            call(v = "a".repeat(32), rawQuery = "v=${"a".repeat(32)}"),
            call(v = "not-a-md5", rawQuery = "v=not-a-md5"),
        ).forEach { response ->
            assertEquals(
                LevelCachePolicy.SHORT,
                response.getHeader(HttpHeaders.CACHE_CONTROL),
                "版本号缺失/不等/非法时必须是短缓存（自愈路径）",
            )
        }
    }

    @Test
    fun versionProbeReturnsBothVariantsAndShortCacheHeader() {
        val (response, result) = callVersion()

        assertEquals(LevelCachePolicy.SHORT, response.getHeader(HttpHeaders.CACHE_CONTROL), "探测端点只给短缓存")
        assertNull(response.getHeader(HttpHeaders.ETAG), "探测端点无需 ETag")
        val versions = result.data
        assertNotNull(versions)
        assertEquals(fullVersion, versions!!.full)
        assertEquals(liteVersion, versions.lite, "两个变体各自独立版本号")
    }

    @Test
    fun matchingIfNoneMatchYields304WithNoBody() {
        // 浏览器/WAF 带 If-None-Match 回来时应换到 304：省掉整份响应体（full 变体 660K）。
        // 返回 null 是必须的——照常返回 MaaResult 的话 304 也会被写出 body。
        response = MockHttpServletResponse()
        val request = getRequest("v=$fullVersion").apply { addHeader(HttpHeaders.IF_NONE_MATCH, "W/\"$fullVersion\"") }
        val result = ArkLevelController(v1Service, v2Service, request, response).getLevelsV2(fullVersion, false, false)

        assertNull(result, "命中条件请求时不返回响应体")
        assertEquals(304, response.status)
        assertEquals("W/\"$fullVersion\"", response.getHeader(HttpHeaders.ETAG), "304 也应带回 ETag")
        assertEquals(LevelCachePolicy.IMMUTABLE, response.getHeader(HttpHeaders.CACHE_CONTROL), "304 仍带缓存头")
    }

    @Test
    fun staleIfNoneMatchStillReturnsCurrentData() {
        // 版本号变化后客户端带着旧 ETag 回来：必须给 200 + 当前数据（自愈路径），不能给 304
        response = MockHttpServletResponse()
        val request = getRequest("v=$fullVersion").apply { addHeader(HttpHeaders.IF_NONE_MATCH, "W/\"${"0".repeat(32)}\"") }
        val result = ArkLevelController(v1Service, v2Service, request, response).getLevelsV2(fullVersion, false, false)

        assertNotNull(result, "旧 ETag 不应命中 304")
        assertEquals(fullVersion, result!!.data?.version)
        assertEquals(200, response.status)
    }

    @Test
    fun conditionalRequestNeverFiresWhenETagIsAbsent() {
        // 无 If-None-Match 时必须正常 200，不应因为「响应里已写过 ETag」而被误判为命中
        val response = call(v = fullVersion, rawQuery = "v=$fullVersion")

        assertEquals(200, response.status)
        assertEquals("W/\"$fullVersion\"", response.getHeader(HttpHeaders.ETAG))
    }

    @Test
    fun bodyCarriesVersionSoClientCanUpdateWithoutReadingCustomHeaders() {
        // version 必须在 body 里：CorsConfig 没配 exposedHeaders，跨域下前端读不到自定义响应头。
        // 这里断言返回对象（写响应体由消息转换器完成，序列化形态由 ArkLevelSerializationTest 锁死）。
        response = MockHttpServletResponse()
        val result = ArkLevelController(v1Service, v2Service, getRequest(), response)
            .getLevelsV2(null, lite = false, withSize = false)!!

        assertEquals(fullVersion, result.data?.version, "响应体应带回版本号")
        assertEquals(200, result.statusCode)
        assertEquals(listOf("full"), result.data?.levels?.map { it.levelId })
    }

    @Test
    fun v1EndpointIsDelegatedToUnchangedServiceAndSetsNoCacheHeaders() {
        // 原接口保持原样：缓存头由 MaaEtagHeaderFilter 统一处理（v2 路径未注册该 filter），
        // 控制器自身不设任何缓存头。这里只验证它仍然只依赖 v1 service。
        every { v1Service.arkLevelInfos } returns emptyList()
        response = MockHttpServletResponse()
        val result = ArkLevelController(v1Service, v2Service, getRequest(), response).getLevels()

        assertEquals(200, result.statusCode)
        assertEquals(emptyList<Any>(), result.data)
        assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL), "v1 端点不应由控制器设置缓存头")
    }
}
