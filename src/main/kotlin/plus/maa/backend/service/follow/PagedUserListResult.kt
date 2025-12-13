package plus.maa.backend.service.follow

import kotlinx.serialization.Serializable
import plus.maa.backend.repository.entity.MaaUser

@Serializable
data class PagedUserListResult(val total: Long, val paged: List<MaaUser>)
