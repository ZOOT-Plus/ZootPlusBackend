package plus.maa.backend.config

import com.fasterxml.jackson.databind.Module
import org.ktorm.database.Database
import org.ktorm.jackson.KtormModule
import org.ktorm.logging.ConsoleLogger
import org.ktorm.logging.LogLevel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class KtormConfig(val dataSource: DataSource) {
    @Bean
    fun database(): Database {
        return Database.connectWithSpringSupport(
            dataSource,
            logger = ConsoleLogger(threshold = LogLevel.DEBUG)
        )
    }

    @Bean
    fun ktormModule(): Module {
        return KtormModule()
    }
}
