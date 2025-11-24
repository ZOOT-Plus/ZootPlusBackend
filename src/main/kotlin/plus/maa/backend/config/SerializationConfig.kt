package plus.maa.backend.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import plus.maa.backend.common.serialization.defaultJson

@Configuration
class SerializationConfig : WebMvcConfigurer {

    @OptIn(ExperimentalSerializationApi::class)
    @Bean
    fun kotlinJson(): Json = Json(defaultJson) {
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Bean
    fun kotlinSerializationHttpMessageConverter(json: Json) =
        KotlinSerializationJsonHttpMessageConverter(json)

}
