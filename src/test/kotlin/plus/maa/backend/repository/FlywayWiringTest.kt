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
        val migrations = migrationFileNames(Path.of("src/main/resources/db/migration"))
        assertTrue(migrations.isNotEmpty(), "迁移目录不应为空")

        assertEquals(
            (1..migrations.size).toList(),
            migrationVersions(migrations),
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
    fun migrationOrderingHandlesDoubleDigitVersions() {
        // 直接锁住这条意见指出的缺陷：文件名字典序会把 V10 排在 V2 之前（"V10__" < "V2__"），
        // 于是加入第 10 个迁移时，「版本号连续」的断言会误报失败——而 Flyway 自身按数值处理，
        // 排序依据必须与它一致。用临时目录构造 V1/V2/V10 复现该场景，不依赖仓库当前恰好只有两个迁移。
        val dir = Files.createTempDirectory("flyway-migrations").also { tmp ->
            listOf("V1__init.sql", "V2__add_column.sql", "V10__tenth.sql").forEach { name ->
                Files.createFile(tmp.resolve(name))
            }
        }
        try {
            val migrations = migrationFileNames(dir)

            // 字典序下的原始顺序：V10 会跑到最前面，这正是缺陷的成因（'0' < '_'，故 "V10__" 排在 "V1__" 前）
            assertEquals(
                listOf("V10__tenth.sql", "V1__init.sql", "V2__add_column.sql"),
                migrations.sorted(),
                "前提：文件名字典序确实把 V10 排在 V1/V2 之前（否则本测试失去意义）",
            )
            assertEquals(listOf(1, 2, 10), migrationVersions(migrations), "版本号必须按数值排序，不能按文件名")
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    /** 迁移目录下的 SQL 文件名（不排序，排序交给 [migrationVersions]）。 */
    private fun migrationFileNames(dir: Path): List<String> = Files.list(dir).use { stream ->
        stream.map { it.fileName.toString() }
            .filter { it.startsWith("V") && it.endsWith(".sql") }
            .toList()
    }

    /**
     * 解析并按**数值**排序迁移版本号（与 Flyway 的排序依据一致）。
     *
     * 用 `toIntOrNull`：非数字版本号（如 `V2_1__x.sql`）得到 null，让调用方的断言失败并带上原始文件名，
     * 比 `toInt()` 直接抛异常更可读。
     */
    private fun migrationVersions(migrations: List<String>): List<Int?> = migrations
        .map { it.substringAfter("V").substringBefore("__").toIntOrNull() }
        .sortedBy { it }

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
