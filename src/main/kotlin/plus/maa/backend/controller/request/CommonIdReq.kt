package plus.maa.backend.controller.request

import jakarta.validation.constraints.NotNull
import kotlinx.serialization.Serializable

/**
 * @author dragove
 * create on 2024-01-05
 */
@Serializable
data class CommonIdReq<T>(
    @field:NotNull(message = "id必填")
    val id: T,
)
