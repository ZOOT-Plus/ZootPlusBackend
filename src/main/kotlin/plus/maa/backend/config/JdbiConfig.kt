package plus.maa.backend.config

import org.jdbi.v3.cache.caffeine.CaffeineCachePlugin
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.core.statement.Slf4JSqlLogger
import org.jdbi.v3.postgres.PostgresPlugin
import org.jdbi.v3.sqlobject.SqlObjectPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * Jdbi 基础设施配置。
 *
 * 缓存：CaffeineCachePlugin 把模板与 SQL 解析缓存切换到 Caffeine。其中
 * FreemarkerEngine 实现 TemplateEngine.Parsing，其解析结果（freemarker Template 对象）
 * 按模板字符串缓存；ColonPrefixSqlParser 的解析结果（参数位置等）按渲染后 SQL 缓存——
 * 动态条件（querySets 等）的不同组合各缓存一份，避免重复解析。
 */
@Configuration
class JdbiConfig(val dataSource: DataSource) {
    @Bean
    fun jdbi(): Jdbi {
        return Jdbi.create(dataSource)
            .setSqlLogger(Slf4JSqlLogger())
            .installPlugin(SqlObjectPlugin())
            .installPlugin(KotlinPlugin())
            .installPlugin(PostgresPlugin())
            .installPlugin(CaffeineCachePlugin())
    }
}
