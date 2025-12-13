package plus.maa.backend.controller.request.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable

/**
 * @author AnselYuki
 */
@Serializable
data class LoginDTO(
    @field:NotBlank(message = "邮箱格式错误")
    @field:Email(message = "邮箱格式错误")
    val email: String,
    @field:NotBlank(message = "请输入用户密码")
    val password: String,
)