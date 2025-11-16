package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import plus.maa.backend.config.doc.RequireJwt
import plus.maa.backend.config.security.AuthenticationHelper
import plus.maa.backend.controller.request.user.LoginDTO
import plus.maa.backend.controller.request.user.PasswordResetDTO
import plus.maa.backend.controller.request.user.PasswordResetVCodeDTO
import plus.maa.backend.controller.request.user.PasswordUpdateDTO
import plus.maa.backend.controller.request.user.RefreshReq
import plus.maa.backend.controller.request.user.RegisterDTO
import plus.maa.backend.controller.request.user.SendRegistrationTokenDTO
import plus.maa.backend.controller.request.user.UserInfoUpdateDTO
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.success
import plus.maa.backend.controller.response.user.MaaLoginRsp
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.service.EmailService
import plus.maa.backend.service.UserService

/**
 * 用户相关接口
 * [前端api约定文件](https://github.com/MaaAssistantArknights/maa-copilot-frontend/blob/dev/src/apis/auth.ts)
 *
 * @author AnselYuki
 */
@Tag(name = "CopilotUser", description = "用户管理")
@RequestMapping("/user")
@Validated
@RestController
class UserController(
    private val userService: UserService,
    private val emailService: EmailService,
    private val helper: AuthenticationHelper,
) {
    /**
     * 更新当前用户的密码(根据原密码)
     *
     * @return http响应
     */
    @Operation(summary = "修改当前用户密码", description = "根据原密码")
    @ApiResponse(description = "修改密码结果")
    @RequireJwt
    @PostMapping("/update/password")
    fun updatePassword(@RequestBody updateDTO: @Valid PasswordUpdateDTO): MaaResult<Unit> {
        userService.modifyPassword(helper.userId, updateDTO.newPassword, updateDTO.originalPassword)
        return success()
    }

    /**
     * 更新用户详细信息
     *
     * @param updateDTO 用户信息参数
     * @return http响应
     */
    @Operation(summary = "更新用户详细信息")
    @ApiResponse(description = "更新结果")
    @RequireJwt
    @PostMapping("/update/info")
    fun updateInfo(@RequestBody updateDTO: @Valid UserInfoUpdateDTO): MaaResult<Unit> {
        userService.updateUserInfo(helper.userId, updateDTO)
        return success()
    }

    /**
     * 邮箱重设密码
     *
     * @param passwordResetDTO 通过邮箱修改密码请求
     * @return 成功响应
     */
    @PostMapping("/password/reset")
    @Operation(summary = "重置密码")
    @ApiResponse(description = "重置密码结果")
    fun passwordReset(@RequestBody passwordResetDTO: @Valid PasswordResetDTO): MaaResult<Unit> {
        // 校验用户邮箱是否存在
        userService.checkUserExistByEmail(passwordResetDTO.email)
        userService.modifyPasswordByActiveCode(passwordResetDTO)
        return success()
    }

    /**
     * 验证码重置密码功能：
     * 发送验证码用于重置
     *
     * @return 成功响应
     */
    @PostMapping("/password/reset_request")
    @Operation(summary = "发送用于重置密码的验证码")
    @ApiResponse(description = "验证码发送结果")
    fun passwordResetRequest(@RequestBody passwordResetVCodeDTO: @Valid PasswordResetVCodeDTO): MaaResult<Unit> {
        // 校验用户邮箱是否存在
        userService.checkUserExistByEmail(passwordResetVCodeDTO.email)
        emailService.sendVCode(passwordResetVCodeDTO.email)
        return success()
    }

    /**
     * 刷新token
     *
     * @param request http请求，用于获取请求头
     * @return 成功响应
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新token")
    @ApiResponse(description = "刷新token结果")
    fun refresh(@RequestBody request: RefreshReq): MaaResult<MaaLoginRsp> {
        val res = userService.refreshToken(request.refreshToken)
        return success(res)
    }

    /**
     * 用户注册
     *
     * @param user 传入用户参数
     * @return 注册成功用户信息摘要
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    @ApiResponse(description = "注册结果")
    fun register(@RequestBody user: @Valid RegisterDTO): MaaResult<MaaUserInfo> = success(userService.register(user))

    /**
     * 获得注册时的验证码
     */
    @PostMapping("/sendRegistrationToken")
    @Operation(summary = "注册时发送验证码")
    @ApiResponse(description = "发送验证码结果", responseCode = "204")
    fun sendRegistrationToken(@RequestBody regDTO: @Valid SendRegistrationTokenDTO): MaaResult<Unit> {
        userService.sendRegistrationToken(regDTO)
        return success()
    }

    /**
     * 用户登录
     *
     * @param user 登录参数
     * @return 成功响应，荷载JwtToken
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    @ApiResponse(description = "登录结果")
    fun login(@RequestBody user: @Valid LoginDTO): MaaResult<MaaLoginRsp> = success("登陆成功", userService.login(user))

    /**
     * 查询用户信息
     */
    @GetMapping("/info")
    @Operation(summary = "查询用户信息")
    @ApiResponse(responseCode = "200", description = "用户详情信息")
    @ApiResponse(responseCode = "404", content = [Content()])
    fun getUserInfo(@RequestParam userId: String): MaaResult<MaaUserInfo> {
        val userInfo =
            userService.get(userId.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user ID"))
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return success(userInfo)
    }

    /**
     * 用户模糊搜索
     */
    @GetMapping("/search")
    @Operation(summary = "用户模糊搜索")
    @ApiResponse(description = "模糊搜索匹配结果")
    fun searchUsers(
        @RequestParam userName: String,
        @RequestParam page: Int = 1,
        @Max(50, message = "查询用户量不能超过50") @RequestParam size: Int = 10,
    ): MaaResult<List<MaaUserInfo>> {
        val result = userService.search(userName, (page - 1) * size, size)
        return success(result.map(::MaaUserInfo))
    }
}
