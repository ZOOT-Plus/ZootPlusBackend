package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.TestDbSupport
import java.time.LocalDateTime

/**
 * 验证 jdbi3-core 3.54.0 内置 [org.jdbi.v3.core.argument.JavaTimeArgumentFactory]
 * （经 SetObjectArgumentFactory，setObject(Types.TIMESTAMP)）已支持 LocalDateTime 绑定，
 * 无需在 [UserRepository] 中额外注册自定义工厂。
 *
 * 关键点：[TestDbSupport] 的 sharedJdbi **未**注册任何自定义 LocalDateTime ArgumentFactory，
 * 仅靠内置工厂。以下用例若能正确写入并读回 LocalDateTime，即证明内置工厂足够，
 * 此前 UserRepository 中的自定义工厂属冗余（其行为与内置等价：均绑定为 PG timestamp），
 * 已据此删除。
 */
class LocalDateTimeArgumentFactoryTest : TestDbSupport() {

    /** 直接用未注册自定义工厂的 sharedJdbi 绑定 LocalDateTime，毫秒精度往返。 */
    @Test
    fun builtInFactoryBindsLocalDateTimeMillisecondPrecision() {
        val ts = LocalDateTime.of(2025, 1, 2, 3, 4, 5, 678_000_000)
        insertFollow(1L, 2L, ts)

        val back = selectUpdatedAt(1L, 2L)
        assertEquals(ts, back, "内置工厂应能将 LocalDateTime 绑定到 timestamp(3) 列并正确读回")
    }

    /**
     * 纳秒以下精度由数据库 timestamp(3) 按四舍五入到毫秒（PG 语义为 round，非 truncate）；
     * 证明内置工厂 setObject(TIMESTAMP) 与列精度一致（与自定义 setTimestamp 行为等价）。
     */
    @Test
    fun builtInFactoryRoundsSubMillisByColumnPrecision() {
        val ts = LocalDateTime.of(2025, 1, 2, 3, 4, 5, 678_901_234)
        insertFollow(1L, 2L, ts)

        val back = selectUpdatedAt(1L, 2L)
        // PG timestamp(3) round 到最近毫秒：678_901_234ns → 679_000_000ns
        assertEquals(ts.withNano(679_000_000), back, "timestamp(3) 列应将纳秒四舍五入到毫秒")
    }

    /** null LocalDateTime 也应被内置工厂正确绑定为 SQL NULL。 */
    @Test
    fun builtInFactoryBindsNullLocalDateTime() {
        // user_follow.updated_at 为 not null，故用 site_message.read_at（可空 timestamp(3)）验证 null 绑定
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO site_message (receiver_id, sender_id, sender_name, type, title, content, read_at, created_at)
                VALUES (:receiverId, :senderId, :senderName, :type, :title, :content, :readAt, :createdAt)
                """.trimIndent(),
            )
                .bind("receiverId", 1L)
                .bind("senderId", 2L)
                .bind("senderName", "sys")
                .bind("type", "SYSTEM")
                .bind("title", "t")
                .bind("content", "c")
                .bind("readAt", null as LocalDateTime?)
                .bind("createdAt", LocalDateTime.of(2025, 1, 1, 0, 0, 0))
                .execute()
        }
        val readAt = jdbi.withHandle<LocalDateTime?, Exception> { handle ->
            handle.createQuery("SELECT read_at FROM site_message WHERE receiver_id = :id")
                .bind("id", 1L)
                .mapTo(LocalDateTime::class.java)
                .findFirst()
                .orElse(null)
        }
        assertEquals(null, readAt, "内置工厂应将 null LocalDateTime 绑定为 SQL NULL")
    }

    private fun insertFollow(userId: Long, followUserId: Long, updatedAt: LocalDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO user_follow (user_id, follow_user_id, special_follow, updated_at)
                VALUES (:userId, :followUserId, false, :updatedAt)
                """.trimIndent(),
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .bind("updatedAt", updatedAt)
                .execute()
        }
    }

    private fun selectUpdatedAt(userId: Long, followUserId: Long): LocalDateTime = jdbi.withHandle<LocalDateTime, Exception> { handle ->
        handle.createQuery("SELECT updated_at FROM user_follow WHERE user_id = :userId AND follow_user_id = :followUserId")
            .bind("userId", userId)
            .bind("followUserId", followUserId)
            .mapTo(LocalDateTime::class.java)
            .one()
    }
}
