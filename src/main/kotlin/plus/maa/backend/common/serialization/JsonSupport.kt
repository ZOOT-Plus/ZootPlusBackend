package plus.maa.backend.common.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DEFAULT_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
private val defaultZone = ZoneId.of("Asia/Shanghai")
private val defaultDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern(DEFAULT_DATE_PATTERN)

val defaultJson = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    this.serializersModule = SerializersModule {
        contextual(LocalDateTime::class, LocalDateTimeAsStringSerializer)
        contextual(Instant::class, InstantAsStringSerializer)
    }
}

object LocalDateTimeAsStringSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDateTime {
        val text = decoder.decodeString()
        return try {
            LocalDateTime.parse(text, defaultDateTimeFormatter)
        } catch (ex: Exception) {
            throw SerializationException("Cannot parse LocalDateTime: $text", ex)
        }
    }

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.format(defaultDateTimeFormatter))
    }
}

object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            LocalDateTime.parse(text, defaultDateTimeFormatter).atZone(defaultZone).toInstant()
        } catch (ex: Exception) {
            throw SerializationException("Cannot parse Instant: $text", ex)
        }
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.atZone(defaultZone).toLocalDateTime().format(defaultDateTimeFormatter))
    }
}
