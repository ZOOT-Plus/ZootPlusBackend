package plus.maa.backend.config

import org.jdbi.v3.cache.caffeine.CaffeineCachePlugin
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.core.statement.Slf4JSqlLogger
import org.jdbi.v3.postgres.PostgresPlugin
import org.jdbi.v3.sqlobject.SqlObjectPlugin
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
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
 *
 * **与 Flyway 的顺序**：[DependsOnDatabaseInitialization] 让本 bean 在「数据库初始化完成」之后才创建。
 * Flyway 的迁移器（`FlywayMigrationInitializer`）被 Spring Boot 识别为 database initializer，
 * 因此这条注解等价于「迁移跑完才允许拿到 `Jdbi`」。Spring Boot 会自动给 JPA/JDBC 系组件加这层依赖，
 * 但 Jdbi 是第三方库、不在其自动探测范围内（官方文档：第三方数据访问库需用本注解标注），故必须显式写。
 *
 * 背景：`SegmentService.afterPropertiesSet` 会在启动期查 `copilot` 表。全新空库上若先跑到它、
 * 再跑迁移，就会抛 `relation "copilot" does not exist` 并让应用整个起不来（实测）。
 * 标注在 `Jdbi` 这一层而非某个具体服务：所有库访问都经由 `Jdbi`，新增的启动期查库代码自动被覆盖，
 * 不需要每个新服务各记一次。
 *
 * 注意：不能用「构造参数注入 `ObjectProvider<FlywayMigrationInitializer>`」来建立顺序——
 * `ObjectProvider` 是惰性句柄，注入它不产生实际依赖，实测无效。
 */
@Configuration
class JdbiConfig(val dataSource: DataSource) {
    @Bean
    @DependsOnDatabaseInitialization
    fun jdbi(): Jdbi {
        return Jdbi.create(dataSource)
            .setSqlLogger(Slf4JSqlLogger())
            .installPlugin(SqlObjectPlugin())
            .installPlugin(KotlinPlugin())
            .installPlugin(PostgresPlugin())
            .installPlugin(CaffeineCachePlugin())
    }
}
