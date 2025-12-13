package plus.maa.backend.controller.request.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable

@Serializable
data class SendRegistrationTokenDTO(
    @field:NotBlank(message = "邮箱格式错误")
    @field:Email(message = "邮箱格式错误")
    val email: String,
)