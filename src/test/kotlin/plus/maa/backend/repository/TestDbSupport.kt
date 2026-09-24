package plus.maa.backend.repository

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.postgres.PostgresPlugin
import org.jdbi.v3.sqlobject.SqlObjectPlugin
import org.junit.jupiter.api.BeforeEach
import javax.sql.DataSource

/**
 * 测试数据库基类。
 *
 * - 整个测试 JVM 只启动一次 zonky embedded PostgreSQL（companion 懒加载单例，JVM 退出时关闭），
 *   避免 zonky 默认按测试类逐个启动的开销。
 * - 建表走 **Flyway**（classpath:db/migration 下的 V1__init.sql 与 V2__zhparser_document_search.sql，
 *   与生产共用同一套迁移；V2 在无 zhparser 扩展的 embedded PG 中会自动跳过）；
 *   zonky 官方也有 FlywayPreparer 配套（JUnit5 extension 体系），此处基类是自定义单例，
 *   直接调 Flyway API 效果等价。
 * - embedded PG 没有 zhparser，迁移后额外用内置 `simple` 配置建一个同名 `chinese_zh` 替身，
 *   使 FTS 查询的 SQL 形状 / 参数绑定 / AND 语义可被测试覆盖（见 [FTS_STANDIN_CREATE_SQL]）。
 * - 每个测试方法前自动 `TRUNCATE` 全部业务表 `RESTART IDENTITY CASCADE`，保证自增 ID 行为可预测。
 *
 * 后续测试类直接继承本类即可，无需任何 Spring 上下文。
 */
abstract class TestDbSupport {

    /** Jdbi 访问入口（插件配置与 main 的 JdbiConfig 一致）。 */
    protected val jdbi: Jdbi
        get() = sharedJdbi

    /** 底层 DataSource（原生 JDBC 测试也会用到）。 */
    protected val dataSource: DataSource
        get() = sharedDataSource

    @BeforeEach
    fun truncateAll() {
        sharedDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(TRUNCATE_ALL_SQL)
            }
        }
    }

    private companion object {
        /** 全部 9 张业务表（与 V1__init.sql 一致），按外键依赖从后往前截断。 */
        private val TRUNCATE_ALL_SQL =
            """
            TRUNCATE TABLE "user", user_follow, site_message, copilot, copilot_operator,
                comments_area, rating, copilot_set, ark_level
            RESTART IDENTITY CASCADE
            """.trimIndent()

        private val embeddedPostgres: EmbeddedPostgres by lazy {
            EmbeddedPostgres.builder().start().also { postgres ->
                Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
                migrateSchema(postgres)
            }
        }

        private val sharedDataSource: DataSource by lazy { embeddedPostgres.postgresDatabase }

        private val sharedJdbi: Jdbi by lazy {
            Jdbi.create(sharedDataSource)
                .installPlugin(SqlObjectPlugin())
                .installPlugin(KotlinPlugin())
                .installPlugin(PostgresPlugin())
        }

        private fun migrateSchema(postgres: EmbeddedPostgres) {
            Flyway.configure()
                .dataSource(postgres.postgresDatabase)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            createFtsStandin(postgres)
        }

        private const val FTS_STANDIN_EXISTS_SQL =
            "SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese_zh' AND cfgnamespace = 'public'::regnamespace"

        /**
         * embedded PG 没有 zhparser，V2 会整段跳过 → `chinese_zh` 不存在，FTS 查询会直接报
         * `text search configuration "chinese_zh" does not exist`。
         * 这里用内置 `simple` 配置复制一个同名替身，让 FTS 的 SQL 形状 / 绑定 / 语义可以被断言。
         *
         * 注意：替身使用 default parser，中文整段会被当作一个词，因此 **不能** 用它验证中文分词质量，
         * 那需要真实 zhparser（见 docs/zhparser-migration.md）。
         */
        private const val FTS_STANDIN_CREATE_SQL =
            "CREATE TEXT SEARCH CONFIGURATION chinese_zh (COPY = pg_catalog.simple)"

        private fun createFtsStandin(postgres: EmbeddedPostgres) {
            postgres.postgresDatabase.connection.use { conn ->
                val exists = conn.createStatement().use { stmt ->
                    stmt.executeQuery(FTS_STANDIN_EXISTS_SQL).use { rs -> rs.next() }
                }
                if (!exists) {
                    conn.createStatement().use { it.execute(FTS_STANDIN_CREATE_SQL) }
                }
            }
        }
    }
}
