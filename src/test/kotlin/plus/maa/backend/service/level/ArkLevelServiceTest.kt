package plus.maa.backend.service.level

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration.Companion.minutes

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ArkLevelServiceTest(
    @Autowired
    private val service: ArkLevelService,
) {

    @Order(1)
    @Test
    fun testSyncLevel() = runTest(timeout = 3.minutes) {
        service.syncLevelData()
    }

    @Order(2)
    @Test
    fun testUpdateActivitiesOpenStatus() = runTest(timeout = 3.minutes) {
        service.updateActivitiesOpenStatus()
    }

    @Order(3)
    @Test
    fun testUpdateCrisisV2OpenStatus() = runTest(timeout = 3.minutes) {
        service.updateCrisisV2OpenStatus()
    }
}
