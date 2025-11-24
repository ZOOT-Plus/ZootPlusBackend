package plus.maa.backend.common.controller

import kotlinx.serialization.Serializable

@Serializable
data class PagedDTO<T>(
    val hasNext: Boolean,
    val page: Int,
    val total: Long,
    val data: List<T>,
)
