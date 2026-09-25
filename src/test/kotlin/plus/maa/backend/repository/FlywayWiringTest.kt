package plus.maa.backend.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Flyway 接线与迁移文件的静态约束。
 *
 * 防的是一类**静默**故障：迁移文件写好了、`spring.flyway.enabled: true` 也配了，但 Spring Boot 4 把
 * Flyway 自动配置拆到了独立的 `spring-boot-flyway` 模块——只依赖 `flyway-core` 时
 * `FlywayAutoConfiguration` 根本不会被加载，迁移永不执行。表现是「应用启动正常、表却不存在」，
 * 本地和 CI 都察觉不到（CI 是空库，第一个查询就报错也容易被当成环境问题）。
 *
 * 真正跑一遍迁移需要完整 Spring 上下文（本仓库的 `@SpringBootTest` 依赖 gitignore 的外部配置），
 * 故这里退一步，用类路径上是否存在自动配置类来判定接线是否完整。
 */
class FlywayWiringTest {

    @Test
    fun flywayAutoConfigurationIsOnClasspath() {
        // 自动配置类来自 spring-boot-flyway 模块；缺失即表示迁移不会被执行
        val autoConfiguration = Class.forName("org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")

        assertEquals("org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration", autoConfiguration.name)
    }

    @Test
    fun migrationFilesAreVersionedSequentiallyFromOne() {
        // Flyway 要求版本号严格递增且不重复；顺序错乱会让迁移在这台机器上能跑、在另一台上跳过。
        // 已发布的迁移只增不改，故这里只断言「从 1 开始、连续、无重复」。
        val migrations = Files.list(Path.of("src/main/resources/db/migration")).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.startsWith("V") && it.endsWith(".sql") }
                .sorted()
                .toList()
        }

        val versions = migrations.map { it.substringAfter("V").substringBefore("__") }
        assertTrue(migrations.isNotEmpty(), "迁移目录不应为空")
        assertEquals(
            (1..migrations.size).map(Int::toString),
            versions,
            "版本号应为从 1 开始的连续整数，实际 $migrations",
        )
        // 只追加不修改已发布版本：文件名的描述部分不得重复
        assertEquals(
            migrations.size,
            migrations.map { it.substringAfter("__") }.distinct().size,
            "迁移描述不得重复，实际 $migrations",
        )
    }

    @Test
    fun updatedAtMigrationDoesNotDefaultTheColumn() {
        // 与 ArkLevelUpdatedAtMigrationTest 互补：那条在真实库上验 schema，这条在源码文本上验意图，
        // 让「有人为了让存量行非空而顺手加上 default now()」在评审前就被测试挡下（那会造成 lite
        // 变体长达 3 个月的体积平台期）。
        // 只看 SQL 语句，剔除 `--` 注释：迁移里正有一段注释专门解释「为什么不加 default now()」。
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V2__add_ark_level_updated_at.sql"))
            .lines()
            .joinToString("\n") { it.substringBefore("--") }

        assertTrue(
            sql.contains("add column if not exists updated_at timestamp(3)"),
            "updated_at 应为可空列且不带默认值，实际 SQL：$sql",
        )
        assertTrue(
            !sql.contains("default", ignoreCase = true),
            "不得给 updated_at 加默认值：存量行必须保持 NULL 以被回填识别，实际 SQL：$sql",
        )
    }
}
