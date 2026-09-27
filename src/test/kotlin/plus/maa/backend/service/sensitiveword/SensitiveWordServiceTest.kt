package plus.maa.backend.service.sensitiveword

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * 需要完整 Spring 上下文（含敏感词词库资源与数据库配置），因此在 CI 中被排除
 * （见 .github/workflows/test.yml）。
 */
@Tag("integration")
@SpringBootTest
class SensitiveWordServiceTest() {

    @Autowired
    private lateinit var service: SensitiveWordService

    @Test
    fun `word in blacklist should trigger an exception`() {
        assertThrows(SensitiveWordException::class.java) {
            service.validate("jb")
        }
    }

    @Test
    fun `word in whitelist should not trigger an exception`() {
        assertDoesNotThrow { service.validate("https://www.bilibili.com/video/BVjbjbjbjbjb/") }
    }
}
