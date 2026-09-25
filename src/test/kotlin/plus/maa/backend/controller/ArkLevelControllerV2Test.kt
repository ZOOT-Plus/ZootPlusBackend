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
import plus.maa.backend.controller.response.copilot.ArkLevelInfoV2
import plus.maa.backend.controller.response.copilot.LevelPayload
import plus.maa.backend.controller.response.copilot.LevelVersions
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.level.ArkLevelV2Service
import plus.maa.backend.service.level.LevelCachePolicy

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

    /** 每次请求重新构造控制器：请求/响应对象是构造注入的，复用会读到上一次写的头。 */
    private var response = MockHttpServletResponse()

    private fun dto(levelId: String) = ArkLevelInfoV2(
        levelId = levelId,
        stageId = "st-1",
        catOne = "活动关卡",
        catTwo = "活动",
        catThree = "C-1",
        name = "关卡",
    )

    private val v2Service = mockk<ArkLevelV2Service> {
        every { payload(lite = false, withSize = false) } returns LevelPayload(fullVersion, listOf(dto("full")))
        every { payload(lite = false, withSize = true) } returns LevelPayload(fullVersion, listOf(dto("full")))
        every { payload(lite = true, withSize = false) } returns LevelPayload(liteVersion, listOf(dto("lite")))
        every { payload(lite = true, withSize = true) } returns LevelPayload(liteVersion, listOf(dto("lite")))
    }

    /** 发一次请求；`rawQuery` 为原始查询串（null 表示无查询串）。 */
    private fun call(
        v: String? = null,
        lite: Boolean = false,
        withSize: Boolean = false,
        rawQuery: String? = null,
    ): MockHttpServletResponse {
        response = MockHttpServletResponse()
        val request = MockHttpServletRequest().apply { queryString = rawQuery }
        ArkLevelController(v1Service, v2Service, request, response).getLevelsV2(v, lite, withSize)
        return response
    }

    /** 发一次版本探测请求。 */
    private fun callVersion(): Pair<MockHttpServletResponse, MaaResult<LevelVersions>> {
        response = MockHttpServletResponse()
        val result = ArkLevelController(v1Service, v2Service, MockHttpServletRequest(), response).getLevelVersions()
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
    fun bodyCarriesVersionSoClientCanUpdateWithoutReadingCustomHeaders() {
        // version 必须在 body 里：CorsConfig 没配 exposedHeaders，跨域下前端读不到自定义响应头。
        // 这里断言返回对象（写响应体由消息转换器完成，序列化形态由 ArkLevelSerializationTest 锁死）。
        response = MockHttpServletResponse()
        val request = MockHttpServletRequest().apply { queryString = null }
        val result = ArkLevelController(v1Service, v2Service, request, response)
            .getLevelsV2(null, lite = false, withSize = false)

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
        val result = ArkLevelController(v1Service, v2Service, MockHttpServletRequest(), response).getLevels()

        assertEquals(200, result.statusCode)
        assertEquals(emptyList<Any>(), result.data)
        assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL), "v1 端点不应由控制器设置缓存头")
    }
}
