package plus.maa.backend.controller.request.user

import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable
import org.hibernate.validator.constraints.Length

/**
 * @author AnselYuki
 */
@Serializable
data class PasswordUpdateDTO(
    @field:NotBlank(message = "请输入原密码")
    val originalPassword: String,
    @field:NotBlank(message = "密码长度必须在8-32位之间")
    @field:Length(min = 8, max = 32, message = "密码长度必须在8-32位之间")
    val newPassword: String,
)
