package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.common.controller.toDto
import plus.maa.backend.config.doc.RequireJwt
import plus.maa.backend.config.security.AuthenticationHelper
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.success
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.service.follow.UserFollowService

@RestController
@RequestMapping("/follow")
@Tag(name = "UserFollow", description = "用户关注管理接口")
class UserFollowController(
    private val userFollowService: UserFollowService,
    private val helper: AuthenticationHelper,
) {

    @Operation(summary = "关注用户")
    @ApiResponse(description = "关注结果")
    @RequireJwt
    @PostMapping("/follow/{followUserId}")
    fun follow(@PathVariable followUserId: Long): MaaResult<Unit> = success(
        userFollowService.follow(helper.userId, followUserId),
    )

    @Operation(summary = "取消关注")
    @ApiResponse(description = "取消关注结果")
    @RequireJwt
    @PostMapping("/unfollow/{followUserId}")
    fun unfollow(@PathVariable followUserId: Long): MaaResult<Unit> = success(
        userFollowService.unfollow(helper.userId, followUserId),
    )

    @Operation(summary = "获取关注列表")
    @ApiResponse(description = "关注列表")
    @RequireJwt
    @GetMapping("/followingList")
    fun getFollowingList(@RequestParam page: Int = 1, @RequestParam size: Int = 10): MaaResult<PagedDTO<MaaUserInfo>> {
        val realPageable = PageRequest.of((page - 1).coerceAtLeast(0), size)
        return success(userFollowService.getFollowingList(helper.userId, realPageable).toDto())
    }

    @Operation(summary = "获取粉丝列表")
    @ApiResponse(description = "粉丝列表")
    @RequireJwt
    @GetMapping("/fansList")
    fun getFansList(@RequestParam page: Int = 1, @RequestParam size: Int = 10): MaaResult<PagedDTO<MaaUserInfo>> {
        val realPageable = PageRequest.of((page - 1).coerceAtLeast(0), size)
        return success(userFollowService.getFansList(helper.userId, realPageable).toDto())
    }
}
