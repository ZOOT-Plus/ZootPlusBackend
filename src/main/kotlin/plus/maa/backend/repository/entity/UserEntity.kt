package plus.maa.backend.repository.entity

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.Table
import org.babyfish.jimmer.sql.meta.UUIDIdGenerator
import java.time.Instant

@Entity
// TODO 可能需要等待JIMMER修复对应问题删除双引号
@Table(name = "\"user\"")
interface UserEntity {
    // 迁移时不标记为主键防止生成
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val userId: String
    val userName: String
    val email: String
    val password: String
    val status: Int
    val pwdUpdateTime: Instant
    val followingCount: Int
    val fansCount: Int
    companion object {
        val UNKNOWN = UserEntity {
            userId = ""
            userName = "未知用户:("
            email = "unknown@unkown.unkown"
            password = "unknown"
        }
    }
}
