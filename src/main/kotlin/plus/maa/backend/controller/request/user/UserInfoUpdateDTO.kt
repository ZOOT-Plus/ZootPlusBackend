package plus.maa.backend.controller.request.user

import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable
import org.hibernate.validator.constraints.Length

/**
 * @author AnselYuki
 */
@Serializable
data class UserInfoUpdateDTO(
    @field:NotBlank(message = "用户名长度应在4-24位之间")
    @field:Length(min = 4, max = 24, message = "用户名长度应在4-24位之间")
    val userName: String,
)
