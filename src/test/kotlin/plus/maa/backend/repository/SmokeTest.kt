package plus.maa.backend.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.ktorm.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 测试基础设施冒烟测试：验证 embedded postgres 启动、Flyway V1__init.sql 建表、
 * jdbi 读写链路与 truncateAll 数据隔离均可用。
 */
class SmokeTest : TestDbSupport() {

    @Test
    fun insertUserThenSelectBack() {
        val userRepo = UserRepository(jdbi)
        val user = UserEntity(
            userName = "smoke-test-user",
            email = "smoke@maa.plus",
            password = "hashed-password",
            status = 0,
            pwdUpdateTime = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            followingCount = 0,
            fansCount = 0,
        )

        userRepo.insertEntity(user)
        assertTrue(user.userId > 0, "bigserial 主键应回填")

        // select 回来断言
        val loaded = userRepo.findById(user.userId)
        assertTrue(loaded != null, "插入的行应能查询到")
        assertEquals(user.userId, loaded!!.userId)
        assertEquals("smoke-test-user", loaded.userName)
        assertEquals("smoke@maa.plus", loaded.email)
        assertEquals("hashed-password", loaded.password)
        assertEquals(0, loaded.status)
        assertEquals(user.pwdUpdateTime, loaded.pwdUpdateTime)
        assertEquals(0, loaded.followingCount)
        assertEquals(0, loaded.fansCount)
    }

    @Test
    fun jdbiInsertThenSelectBack() {
        val pwdUpdateTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        val userId: Long = jdbi.withHandle<Long, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO "user" (user_name, email, password, status, pwd_update_time, following_count, fans_count)
                VALUES (:userName, :email, :password, :status, :pwdUpdateTime, :followingCount, :fansCount)
                """.trimIndent(),
            )
                .bind("userName", "jdbi-smoke-user")
                .bind("email", "jdbi-smoke@maa.plus")
                .bind("password", "hashed-password")
                .bind("status", 0)
                .bind("pwdUpdateTime", pwdUpdateTime)
                .bind("followingCount", 0)
                .bind("fansCount", 0)
                .executeAndReturnGeneratedKeys("user_id")
                .mapTo(Long::class.java)
                .one()
        }
        assertTrue(userId > 0, "jdbi 插入应回填自增主键")

        val row: Map<String, Any?> = jdbi.withHandle<Map<String, Any?>, Exception> { handle ->
            handle.createQuery("SELECT user_name, email, status FROM \"user\" WHERE user_id = :id")
                .bind("id", userId)
                .mapToMap()
                .one()
        }
        assertEquals("jdbi-smoke-user", row["user_name"])
        assertEquals("jdbi-smoke@maa.plus", row["email"])
        assertEquals(0, row["status"])
    }

    @Test
    fun truncateAllClearsRowsAndResetsSequence() {
        val userRepo = UserRepository(jdbi)
        repeat(2) {
            userRepo.insertEntity(
                UserEntity(
                    userName = "user-$it",
                    email = "user-$it@maa.plus",
                    password = "pwd",
                    status = 0,
                    pwdUpdateTime = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    followingCount = 0,
                    fansCount = 0,
                ),
            )
        }
        assertEquals(2, userRepo.count())

        truncateAll()

        assertEquals(0, userRepo.count(), "TRUNCATE 后应无残留数据")
        val fresh = UserEntity(
            userName = "fresh",
            email = "fresh@maa.plus",
            password = "pwd",
            status = 0,
            pwdUpdateTime = Instant.now().truncatedTo(ChronoUnit.MILLIS),
            followingCount = 0,
            fansCount = 0,
        )
        userRepo.insertEntity(fresh)
        assertEquals(1L, fresh.userId, "RESTART IDENTITY 后自增应从 1 重新开始")
    }
}
