package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.success
import plus.maa.backend.controller.response.copilot.ArkLevelInfo
import plus.maa.backend.controller.response.copilot.LevelPayload
import plus.maa.backend.controller.response.copilot.LevelVersions
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.level.ArkLevelV2Service
import plus.maa.backend.service.level.LevelCachePolicy

/**
 * @author john180
 */
@RestController
@Tag(name = "ArkLevelController", description = "关卡数据管理接口")
class ArkLevelController(
    private val arkLevelService: ArkLevelService,
    private val arkLevelV2Service: ArkLevelV2Service,
    // 与 CopilotController 同一约定：注入请求/响应对象以便按端点写缓存头（框架注入的是请求作用域代理）。
    // 用注入而非方法参数，可让 v2 端点的方法签名只留三个业务参数，生成的 OpenAPI 里不会混进容器对象。
    private val request: HttpServletRequest,
    private val response: HttpServletResponse,
) {
    @GetMapping("/arknights/level")
    @ApiResponse(description = "关卡数据")
    @Operation(summary = "获取关卡数据")
    fun getLevels(): MaaResult<List<ArkLevelInfo>> = success(arkLevelService.arkLevelInfos)

    /**
     * 关卡数据的版本化缓存端点（面向本站前端）。
     *
     * 与 v1 的差别只有两点：可用查询参数选择变体（`lite`/`withSize`），以及按「内容寻址 URL + 长缓存」
     * 返回 —— 客户端带上本地版本号 `v`，命中时响应为 `immutable`，浏览器此后永久命中本地缓存，
     * 不产生任何请求。版本号不匹配时返回**当前**数据（不保留历史快照）并短缓存，客户端更新本地版本号
     * 后即进入 immutable 通道，构成自愈路径。
     *
     * 参数顺序与写法固定在 `v` → `lite` → `withSize`，值为 false 的开关**省略不写**：WAF 把查询串计入
     * 缓存键且不做归一化（实测 `?a=1&b=2` 与 `?b=2&a=1` 是两个独立条目），放任变体写法会让同一份内容
     * 在浏览器与 WAF 里各占多份。写法不规范的请求仍返回正确内容，只是走短缓存，不进 immutable 通道。
     *
     * 返回可空：请求带 `If-None-Match` 且与当前版本一致时，[ServletWebRequest.checkNotModified] 会把响应
     * 置为 304 并返回 true，此处返回 null 让 Spring 不写响应体（若照常返回 `MaaResult`，304 也会带 body）。
     * 返回类型可空**不影响**生成的 OpenAPI——实测 schema 仍是 `MaaResultLevelPayload`、响应仍是 `default`。
     */
    @GetMapping("/arknights/level/v2")
    @ApiResponse(description = "关卡数据（版本化缓存）")
    @Operation(summary = "获取关卡数据（v2，内容寻址）")
    fun getLevelsV2(
        @RequestParam(required = false) v: String?,
        @RequestParam(defaultValue = "false") lite: Boolean,
        @RequestParam(defaultValue = "false") withSize: Boolean,
    ): MaaResult<LevelPayload>? {
        val payload = arkLevelV2Service.payload(lite, withSize)
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            LevelCachePolicy.cacheControl(v, payload.version, request.queryString, lite, withSize),
        )
        // 版本号本身就是内容摘要，直接当 ETag 用（零成本，无需像 ShallowEtagHeaderFilter 那样把整个
        // 响应体哈希一遍）。用弱校验：响应会被容器动态压缩，强 ETag 在「同一 URL 有多种内容编码」时
        // 不成立。
        val notModified = ServletWebRequest(request, response).checkNotModified("W/\"${payload.version}\"")
        if (notModified) return null
        return success(payload)
    }

    /**
     * 版本探测端点：一次往返取到两个变体的当前版本号，客户端据此决定是否要重新拉数据。
     *
     * 与内容端点共用 [ArkLevelV2Service] 的同一批缓存条目，因此二者给出的版本号**不可能互相矛盾**。
     */
    @GetMapping("/arknights/level/v2/version")
    @ApiResponse(description = "关卡数据版本号")
    @Operation(summary = "获取关卡数据版本号（v2）")
    fun getLevelVersions(): MaaResult<LevelVersions> {
        response.setHeader(HttpHeaders.CACHE_CONTROL, LevelCachePolicy.SHORT)
        return success(
            LevelVersions(
                full = arkLevelV2Service.payload(lite = false, withSize = false).version,
                lite = arkLevelV2Service.payload(lite = true, withSize = false).version,
            ),
        )
    }
}
