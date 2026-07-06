package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.config.doc.RequireJwt
import plus.maa.backend.config.security.AuthenticationHelper
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.success
import plus.maa.backend.controller.response.message.SiteMessageInfo
import plus.maa.backend.controller.response.message.UnreadMessageCountInfo
import plus.maa.backend.service.SiteMessageService

@RestController
@RequestMapping("/message")
@Tag(name = "SiteMessage", description = "站内信接口")
class MessageController(
    private val siteMessageService: SiteMessageService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "分页查询站内信")
    @ApiResponse(description = "站内信列表")
    @RequireJwt
    @GetMapping("/list")
    fun list(
        @RequestParam page: Int = 1,
        @RequestParam size: Int = 10,
        @RequestParam("unread_only") unreadOnly: Boolean = false,
    ): MaaResult<PagedDTO<SiteMessageInfo>> {
        return success(siteMessageService.list(helper.userId, page, size, unreadOnly))
    }

    @Operation(summary = "查询未读站内信数量")
    @ApiResponse(description = "未读站内信数量")
    @RequireJwt
    @GetMapping("/unreadCount")
    fun unreadCount(): MaaResult<UnreadMessageCountInfo> {
        return success(siteMessageService.unreadCount(helper.userId))
    }

    @Operation(summary = "标记单条站内信已读")
    @ApiResponse(description = "标记结果")
    @RequireJwt
    @PostMapping("/read/{id}")
    fun read(@PathVariable id: Long): MaaResult<Unit> {
        siteMessageService.markRead(helper.userId, id)
        return success()
    }

    @Operation(summary = "标记全部站内信已读")
    @ApiResponse(description = "标记结果")
    @RequireJwt
    @PostMapping("/readAll")
    fun readAll(): MaaResult<Unit> {
        siteMessageService.markAllRead(helper.userId)
        return success()
    }
}
