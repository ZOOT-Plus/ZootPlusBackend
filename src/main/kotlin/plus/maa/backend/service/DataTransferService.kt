package plus.maa.backend.service

import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.util.MimeTypeUtils
import java.io.IOException

/**
 * @author AnselYuki
 */
@Service
class DataTransferService(val json: Json) {
    @OptIn(ExperimentalSerializationApi::class)
    final inline fun <reified T> writeJson(response: HttpServletResponse, value: T, code: Int = HttpStatus.OK.value()) {
        try {
            response.status = code
            response.contentType = MimeTypeUtils.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            json.encodeToStream(value, response.outputStream)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
