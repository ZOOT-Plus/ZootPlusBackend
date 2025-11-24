package plus.maa.backend.controller.request.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable

/**
 * 通过邮件修改密码请求
 */
@Serializable
data class PasswordResetDTO(
    /**
     * 邮箱
     */
    @field:NotBlank(message = "邮箱格式错误")
    @field:Email(message = "邮箱格式错误")
    val email: String,
    /**
     * 验证码
     */
    @field:NotBlank(message = "请输入验证码")
    val activeCode: String,
    /**
     * 修改后的密码
     */
    @field:NotBlank(message = "请输入用户密码")
    val password: String,
)