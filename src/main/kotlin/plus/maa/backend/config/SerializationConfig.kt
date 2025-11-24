package plus.maa.backend.config

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import plus.maa.backend.common.serialization.defaultJson

@Configuration
class SerializationConfig : WebMvcConfigurer {

    @Bean
    fun kotlinJson(): Json = defaultJson()

    @Bean
    fun kotlinSerializationHttpMessageConverter(json: Json) =
        KotlinSerializationJsonHttpMessageConverter(json)

}
