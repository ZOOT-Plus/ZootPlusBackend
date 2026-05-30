package plus.maa.backend.controller.response.user

import kotlinx.serialization.Serializable

@Serializable
enum class RelationType {
    SELF, NONE, FOLLOWING, FOLLOWED_BY, MUTUAL
}
