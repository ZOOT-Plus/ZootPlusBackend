package plus.maa.backend.repository.entity

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.int
import org.ktorm.schema.timestamp
import org.ktorm.schema.varchar
import java.time.Instant

interface UserEntity : Entity<UserEntity> {
    var userId: String
    var userName: String
    var email: String
    var password: String
    var status: Int
    var pwdUpdateTime: Instant
    var followingCount: Int
    var fansCount: Int

    companion object: Entity.Factory<UserEntity>() {
        val UNKNOWN = UserEntity {
            userId = ""
            userName = "未知用户:("
            email = "unknown@unkown.unkown"
            password = "unknown"
        }
    }
}

object Users : Table<UserEntity>("user") {
    val userId = varchar("user_id").primaryKey().bindTo { it.userId }
    val userName = varchar("user_name").bindTo { it.userName }
    val email = varchar("email").bindTo { it.email }
    val password = varchar("password").bindTo { it.password }
    val status = int("status").bindTo { it.status }
    val pwdUpdateTime = timestamp("pwd_update_time").bindTo { it.pwdUpdateTime }
    val followingCount = int("following_count").bindTo { it.followingCount }
    val fansCount = int("fans_count").bindTo { it.fansCount }
}

val Database.users get() = this.sequenceOf(Users)
