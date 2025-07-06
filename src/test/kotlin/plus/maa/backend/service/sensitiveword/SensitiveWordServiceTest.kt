package plus.maa.backend.service.sensitiveword

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

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
