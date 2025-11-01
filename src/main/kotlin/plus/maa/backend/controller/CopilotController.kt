package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import plus.maa.backend.config.doc.RequireJwt
import plus.maa.backend.config.security.AuthenticationHelper
import plus.maa.backend.controller.request.copilot.CopilotCUDRequest
import plus.maa.backend.controller.request.copilot.CopilotQueriesRequest
import plus.maa.backend.controller.request.copilot.CopilotRatingReq
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.fail
import plus.maa.backend.controller.response.MaaResult.Companion.success
import plus.maa.backend.controller.response.copilot.CopilotInfo
import plus.maa.backend.controller.response.copilot.CopilotPageInfo
import plus.maa.backend.service.CopilotService
import plus.maa.backend.service.model.CommentStatus

/**
 * @author LoMu
 * Date  2022-12-25 17:08
 */
@RestController
@RequestMapping("/copilot")
@Tag(name = "CopilotController", description = "作业本体管理接口")
class CopilotController(
    private val copilotService: CopilotService,
    private val helper: AuthenticationHelper,
    private val response: HttpServletResponse,
) {
    @Operation(summary = "上传作业")
    @ApiResponse(description = "上传作业结果")
    @RequireJwt
    @PostMapping("/upload")
    fun uploadCopilot(@RequestBody @Valid request: CopilotCUDRequest): MaaResult<Long> {
        return success(copilotService.upload(helper.userId, request))
    }

    @Operation(summary = "删除作业")
    @ApiResponse(description = "删除作业结果")
    @RequireJwt
    @PostMapping("/delete")
    fun deleteCopilot(@RequestBody request: CopilotCUDRequest): MaaResult<Unit> {
        copilotService.delete(helper.userId, request)
        return success()
    }

    @Operation(summary = "获取作业")
    @ApiResponse(description = "作业信息")
    @GetMapping("/get/{id}")
    fun getCopilotById(@Parameter(description = "作业id") @PathVariable("id") id: Long): MaaResult<CopilotInfo?> {
        val userIdOrIpAddress = helper.obtainUserIdOrIpAddress()
        return copilotService.getCopilotById(userIdOrIpAddress, id)?.let { success(it) }
            ?: fail(404, "作业不存在")
    }

    @Operation(summary = "分页查询作业，提供登录凭据时查询用户自己的作业")
    @ApiResponse(description = "作业信息")
    @GetMapping("/query")
    fun queriesCopilot(@ParameterObject parsed: @Valid CopilotQueriesRequest): MaaResult<CopilotPageInfo> {
        // 三秒防抖，缓解前端重复请求问题
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3, must-revalidate")
        return success(copilotService.queriesCopilot(helper.obtainUserId()?.toLongOrNull(), parsed))
    }

    @Operation(summary = "更新作业")
    @ApiResponse(description = "更新结果")
    @RequireJwt
    @PostMapping("/update")
    fun updateCopilot(@RequestBody @Valid copilotCUDRequest: CopilotCUDRequest): MaaResult<Unit> {
        copilotService.update(helper.userId, copilotCUDRequest)
        return success()
    }

    @Operation(summary = "为作业评分")
    @ApiResponse(description = "评分结果")
    @PostMapping("/rating")
    fun ratesCopilotOperation(@RequestBody @Valid copilotRatingReq: CopilotRatingReq): MaaResult<String> {
        copilotService.rates(helper.obtainUserIdOrIpAddress(), copilotRatingReq)
        return success("评分成功")
    }

    @RequireJwt
    @Operation(summary = "修改通知状态")
    @ApiResponse(description = "success")
    @GetMapping("/status")
    fun modifyStatus(@RequestParam id: @NotBlank Long, @RequestParam status: Boolean): MaaResult<String> {
        copilotService.notificationStatus(helper.userId, id, status)
        return success("success")
    }

    @Operation(summary = "禁用评论区/开启评论区")
    @RequireJwt
    @GetMapping("/ban")
    fun banComments(@RequestParam copilotId: @NotBlank Long, @RequestParam status: CommentStatus): MaaResult<String> {
        copilotService.commentStatus(helper.userId, copilotId, status)
        return success("success")
    }
}
