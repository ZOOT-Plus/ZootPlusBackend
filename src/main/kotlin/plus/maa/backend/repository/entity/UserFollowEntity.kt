package plus.maa.backend.repository.entity

import org.ktorm.entity.Entity
import org.ktorm.schema.Table
import org.ktorm.schema.datetime
import org.ktorm.schema.long
import java.time.LocalDateTime

interface UserFollowEntity : Entity<UserFollowEntity> {
    var userId: Long
    val followUserId: Long
    val updatedAt: LocalDateTime

    companion object : Entity.Factory<UserFollowEntity>()
}


object UserFollows : Table<UserFollowEntity>("user_follow") {
    val userId = long("user_id").primaryKey().bindTo { it.userId }
    val followUserId = long("follow_user_id").primaryKey().bindTo { it.followUserId }
    val updatedAt = datetime("updated_at").bindTo { it.updatedAt }
}
