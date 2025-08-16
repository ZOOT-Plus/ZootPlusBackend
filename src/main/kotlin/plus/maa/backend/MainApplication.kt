package plus.maa.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity

@EnableAsync
@EnableCaching
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableMethodSecurity
class MainApplication

fun main(args: Array<String>) {
    runApplication<MainApplication>(*args)
}
