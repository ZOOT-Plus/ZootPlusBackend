package plus.maa.backend.controller.request.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable
import org.hibernate.validator.constraints.Length

/**
 * @author AnselYuki
 */
@Serializable
data class RegisterDTO(
    @field:NotBlank(message = "邮箱格式错误")
    @field:Email(message = "邮箱格式错误")
    val email: String,
    @field:NotBlank(message = "用户名长度应在4-24位之间")
    @field:Length(min = 4, max = 24, message = "用户名长度应在4-24位之间")
    val userName: String,
    @field:NotBlank(message = "密码长度必须在8-32位之间")
    @field:Length(min = 8, max = 32, message = "密码长度必须在8-32位之间")
    val password: String,
    @field:NotBlank(message = "请输入验证码")
    val registrationToken: String,
)
