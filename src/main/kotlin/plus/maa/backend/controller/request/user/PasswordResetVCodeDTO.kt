package plus.maa.backend.controller.request.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable

/**
 * 通过邮件修改密码发送验证码请求
 */
@Serializable
data class PasswordResetVCodeDTO(
    /**
     * 邮箱
     */
    @field:NotBlank(message = "邮箱格式错误")
    @field:Email(message = "邮箱格式错误")
    val email: String,
)