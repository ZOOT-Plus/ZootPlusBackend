package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import plus.maa.backend.config.doc.RequireJwt
import plus.maa.backend.config.security.AuthenticationHelper
import plus.maa.backend.controller.request.comments.CommentsAddDTO
import plus.maa.backend.controller.request.comments.CommentsDeleteDTO
import plus.maa.backend.controller.request.comments.CommentsQueriesDTO
import plus.maa.backend.controller.request.comments.CommentsRatingDTO
import plus.maa.backend.controller.request.comments.CommentsToppingDTO
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.success
import plus.maa.backend.controller.response.comments.CommentsAreaInfo
import plus.maa.backend.service.CommentsAreaService

/**
 * @author LoMu
 * Date  2023-02-17 14:56
 */
@RestController
@Tag(name = "CommentArea", description = "评论区管理接口")
@RequestMapping("/comments")
class CommentsAreaController(
    private val commentsAreaService: CommentsAreaService,
    private val authHelper: AuthenticationHelper,
) {
    @PostMapping("/add")
    @Operation(summary = "发送评论")
    @ApiResponse(description = "发送评论结果")
    @RequireJwt
    fun sendComments(@RequestBody comments: @Valid CommentsAddDTO): MaaResult<String> {
        commentsAreaService.addComments(authHelper.userId, comments)
        return success("评论成功")
    }

    @GetMapping("/query")
    @Operation(summary = "分页查询评论")
    @ApiResponse(description = "评论区信息")
    fun queriesCommentsArea(@ParameterObject parsed: @Valid CommentsQueriesDTO): MaaResult<CommentsAreaInfo> =
        success(commentsAreaService.queriesCommentsArea(parsed))

    @PostMapping("/delete")
    @Operation(summary = "删除评论")
    @ApiResponse(description = "评论删除结果")
    @RequireJwt
    fun deleteComments(@RequestBody comments: @Valid CommentsDeleteDTO): MaaResult<String> {
        commentsAreaService.deleteComments(authHelper.userId, comments.commentId)
        return success("评论已删除")
    }

    @Operation(summary = "为评论点赞")
    @ApiResponse(description = "点赞结果")
    @RequireJwt
    @PostMapping("/rating")
    fun ratesComments(@RequestBody commentsRatingDTO: @Valid CommentsRatingDTO): MaaResult<String> {
        commentsAreaService.rates(authHelper.obtainUserIdOrIpAddress(), commentsRatingDTO)
        return success("成功")
    }

    @Operation(summary = "为评论置顶/取消置顶")
    @ApiResponse(description = "置顶/取消置顶结果")
    @RequireJwt
    @PostMapping("/topping")
    fun toppingComments(@RequestBody commentsToppingDTO: @Valid CommentsToppingDTO): MaaResult<String> {
        commentsAreaService.topping(authHelper.userId, commentsToppingDTO)
        return success("成功")
    }

    @Operation(summary = "设置通知接收状态")
    @RequireJwt
    @GetMapping("/status")
    fun modifyStatus(@RequestParam id: Long, @RequestParam status: Boolean): MaaResult<String> {
        commentsAreaService.notificationStatus(authHelper.userId, id, status)
        return success("success")
    }
}
