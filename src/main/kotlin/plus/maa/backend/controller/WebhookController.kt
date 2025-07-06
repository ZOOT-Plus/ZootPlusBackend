package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import plus.maa.backend.config.doc.RequireApiKey
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.config.external.Webhook
import plus.maa.backend.task.ArkLevelSyncTask

@Tag(name = "Webhook")
@RequestMapping(value = ["/webhook"], produces = ["application/json"])
@RestController
class WebhookController(properties: MaaCopilotProperties, private val levelTask: ArkLevelSyncTask) {
    val apiKey = properties.webhook.levelSyncApiKey

    @RequireApiKey
    @PostMapping(value = ["/levels/sync"])
    suspend fun levelsUpdate(request: HttpServletRequest) {
        request.checkApiKey()
        levelTask.syncArkLevels()
    }

    @RequireApiKey
    @PostMapping(value = ["/levels/open-status/sync"])
    suspend fun levelOpenStatusUpdate(request: HttpServletRequest) {
        request.checkApiKey()
        levelTask.updateOpenStatus()
    }

    private fun HttpServletRequest.checkApiKey() {
        if (apiKey == Webhook.MISSING) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val key = getHeader("X-API-Key")
        if (key != apiKey) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key")
    }
}
