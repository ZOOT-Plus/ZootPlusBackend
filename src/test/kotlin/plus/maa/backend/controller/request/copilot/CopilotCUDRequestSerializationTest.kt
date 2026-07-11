package plus.maa.backend.controller.request.copilot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.service.model.CopilotSetStatus

/**
 * Verifies the dual serialization contract of the sealed [CopilotCUDRequest]:
 * - kotlinx-serialization (runtime request body): discriminator `type`, snake_case,
 *   and (compat) missing/null `type` defaults to PRTS so old MAA clients keep working.
 * - Jackson (SpringDoc/OpenAPI codegen): same discriminator + subtype mapping.
 *
 * If either side drifts, codegen produces a spec that doesn't match the runtime JSON.
 */
class CopilotCUDRequestSerializationTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val ktx: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val jackson = jacksonObjectMapper()

    @Test
    fun `kotlinx decodes PRTS discriminator to PrtsCUDRequest`() {
        val req: CopilotCUDRequest = ktx.decodeFromString(
            """{"type":"PRTS","content":"{}","id":7,"status":"PUBLIC"}""",
        )
        assertTrue(req is PrtsCUDRequest)
        assertEquals("{}", req.content)
        assertEquals(7L, req.id)
    }

    @Test
    fun `kotlinx decodes VIDEO discriminator to VideoCUDRequest`() {
        val req: CopilotCUDRequest = ktx.decodeFromString(
            """{"type":"VIDEO","content":"{}","id":8}""",
        )
        assertTrue(req is VideoCUDRequest)
        assertEquals(8L, req.id)
        // status has a default; omitted in input should fall back to PUBLIC
        assertEquals(CopilotSetStatus.PUBLIC, req.status)
    }

    @Test
    fun `kotlinx encodes discriminator and snake_case fields`() {
        val json = ktx.encodeToString(
            CopilotCUDRequest.serializer(),
            VideoCUDRequest(content = "c", id = 3, status = CopilotSetStatus.PRIVATE),
        )
        assertTrue(json.contains("""type":"VIDEO"""), json)
        assertTrue(json.contains("""status":"PRIVATE"""), json)
    }

    @Test
    fun `kotlinx defaults missing type discriminator to PRTS for old-client compatibility`() {
        // old MAA clients upload/update without a type field
        val req: CopilotCUDRequest = ktx.decodeFromString(
            """{"content":"{}","id":1,"status":"PUBLIC"}""",
        )
        assertTrue(req is PrtsCUDRequest)
        assertEquals("{}", req.content)
        assertEquals(1L, req.id)
    }

    @Test
    fun `kotlinx treats explicit null type discriminator as PRTS`() {
        val req: CopilotCUDRequest = ktx.decodeFromString(
            """{"type":null,"content":"{}","id":2}""",
        )
        assertTrue(req is PrtsCUDRequest)
    }

    @Test
    fun `jackson decodes PRTS discriminator to PrtsCUDRequest`() {
        val req: CopilotCUDRequest = jackson.readValue(
            """{"type":"PRTS","content":"{}","id":7,"status":"PUBLIC"}""",
        )
        assertTrue(req is PrtsCUDRequest)
        assertEquals(7L, req.id)
    }

    @Test
    fun `jackson decodes VIDEO discriminator to VideoCUDRequest`() {
        val req: CopilotCUDRequest = jackson.readValue(
            """{"type":"VIDEO","content":"{}","id":8,"status":"PUBLIC"}""",
        )
        assertTrue(req is VideoCUDRequest)
        assertEquals(8L, req.id)
    }

    @Test
    fun `jackson encodes discriminator`() {
        val json = jackson.writeValueAsString(
            PrtsCUDRequest(content = "c", id = 3, status = CopilotSetStatus.PRIVATE),
        )
        assertTrue(json.contains("""type":"PRTS"""), json)
    }
}