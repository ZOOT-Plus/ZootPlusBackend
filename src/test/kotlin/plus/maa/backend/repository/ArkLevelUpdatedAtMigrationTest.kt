package plus.maa.backend.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * V2 迁移（`ark_level.updated_at`）落到真实库上的形态。
 *
 * 迁移最容易出错的不是 SQL 语法而是**列的可空性与默认值**：`add column ... default now()` 会把存量行一并
 * 填成迁移时刻，于是 lite 变体在此后 3 个月内返回全部活动关卡（体积从 2.2K 涨到 49.9K），到期再断崖
 * 下跌。这一条在仓库里无法用业务测试发现，必须在 schema 层面锁住「无默认值 + 可空」。
 */
class ArkLevelUpdatedAtMigrationTest : TestDbSupport() {

    @Test
    fun updatedAtColumnAcceptsNullAndHasNoDefault() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                // 显式插入 NULL 必须成功：存量行就是 NULL（含义是「尚未回填」）
                stmt.execute(
                    "INSERT INTO ark_level (level_id, stage_id, sha, width, height, updated_at) " +
                        "VALUES ('lv-null', 'st-null', 'sha-null', 1, 1, NULL)",
                )
                // 不列该列也必须写入 NULL 而不是 now()：DEFAULT 会让存量行被误判进 lite 窗口
                stmt.execute(
                    "INSERT INTO ark_level (level_id, stage_id, sha, width, height) " +
                        "VALUES ('lv-default', 'st-default', 'sha-default', 1, 1)",
                )

                stmt.executeQuery(
                    "SELECT is_nullable, column_default FROM information_schema.columns " +
                        "WHERE table_name = 'ark_level' AND column_name = 'updated_at'",
                ).use { rs ->
                    assertTrue(rs.next(), "updated_at 列应存在于 ark_level")
                    assertEquals("YES", rs.getString("is_nullable"), "必须是可空列（存量行未回填时为 NULL）")
                    assertEquals(null, rs.getString("column_default"), "不得有默认值（DEFAULT now() 会制造 3 个月平台期）")
                }

                stmt.executeQuery(
                    "SELECT updated_at FROM ark_level WHERE level_id IN ('lv-null', 'lv-default') ORDER BY level_id",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(null, rs.getTimestamp("updated_at"), "不指定该列时不应自动填值")
                    assertTrue(rs.next())
                    assertEquals(null, rs.getTimestamp("updated_at"), "显式 NULL 必须能落库")
                }
            }
        }
    }

    @Test
    fun liteIndexExists() {
        // lite 变体的谓词是 (cat_one, updated_at) 组合查询，索引缺失时全表扫描
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'ark_level' " +
                        "AND indexname = 'idx_ark_level_cat_one_updated_at'",
                ).use { rs ->
                    assertNotNull(rs.next(), "联合索引应存在")
                    assertTrue(
                        rs.getString("indexdef").contains("(cat_one, updated_at)"),
                        "索引列顺序应为 (cat_one, updated_at)，实际 ${rs.getString("indexdef")}",
                    )
                }
            }
        }
    }
}
