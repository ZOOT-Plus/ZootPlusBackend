package plus.maa.backend.benchmark

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import plus.maa.backend.controller.request.copilot.CopilotQueriesRequest
import plus.maa.backend.service.CopilotService
import java.util.concurrent.TimeUnit

@SpringBootTest
class CopilotQueryBenchmark(
    @Autowired val copilotService: CopilotService
) {
    val userId = "6828bb1d8ad2ac5001530806"
    val requests = mutableListOf<CopilotQueriesRequest>()

    init {
        val req = CopilotQueriesRequest(limit = 100)
        requests.add(req)
    }

    fun measure(times: Int, fn: () -> Unit): Long {
        val start = System.currentTimeMillis()
        repeat (times) {
            fn()
        }
        val end = System.currentTimeMillis()
        return end - start
    }

    @Test
    fun testQuery() {
        val req = CopilotQueriesRequest(limit = 100, operator = "塞雷娅,~乌尔比安", document = "as", orderBy = "hot")

        val originWarm = measure(200) { copilotService.queriesCopilot(userId, req) }
        val currentWarm = measure(200) { copilotService.queriesCopilotPG(userId, req) }
        println(originWarm)
        println(currentWarm)

        val origin = measure(1000) { copilotService.queriesCopilot(userId, req) }
        TimeUnit.SECONDS.sleep(1L)
        val current = measure(1000) { copilotService.queriesCopilotPG(userId, req) }
        println("origin consumes: $origin ms, current consumes: $current ms")
    }

}
