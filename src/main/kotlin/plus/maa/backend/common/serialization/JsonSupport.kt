package plus.maa.backend.common.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DEFAULT_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"
private val defaultZone = ZoneId.of("UTC")
private val defaultDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern(DEFAULT_DATE_PATTERN).withZone(defaultZone)

@OptIn(ExperimentalSerializationApi::class)
val defaultJson = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
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
        encoder.encodeString(value.atZone(defaultZone).format(defaultDateTimeFormatter))
    }
}

object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.from(defaultDateTimeFormatter.parse(text))
        } catch (ex: Exception) {
            throw SerializationException("Cannot parse Instant: $text", ex)
        }
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(defaultDateTimeFormatter.format(value))
    }
}

@Configuration
class JacksonConfig {
    @Bean
    fun jsonCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder: Jackson2ObjectMapperBuilder ->
            val formatter = DateTimeFormatter.ofPattern(DEFAULT_DATE_PATTERN).withZone(defaultZone)
            builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            builder.serializers(LocalDateTimeSerializer(formatter))
        }

    class LocalDateTimeSerializer(
        private val formatter: DateTimeFormatter,
    ) : StdSerializer<LocalDateTime>(LocalDateTime::class.java) {
        @Throws(IOException::class)
        override fun serialize(value: LocalDateTime, gen: JsonGenerator, provider: SerializerProvider) {
            gen.writeString(value.atZone(ZoneId.systemDefault()).format(formatter))
        }
    }
}

