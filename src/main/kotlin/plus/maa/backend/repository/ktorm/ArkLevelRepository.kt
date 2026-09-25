package plus.maa.backend.repository.ktorm

import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings
import org.jdbi.v3.sqlobject.kotlin.BindKotlin
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys
import org.jdbi.v3.sqlobject.statement.SqlBatch
import org.jdbi.v3.sqlobject.statement.SqlUpdate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.ArkLevelEntity
import java.time.LocalDateTime

/**
 * ark_level 表仓储。
 *
 * - save / saveAll：单条/批量 INSERT ... ON CONFLICT (id) DO UPDATE 全列覆盖（upsert），
 *   由数据库决定插入或更新，不预探 existsById。
 * - 显式非 0 id：按给定值插入且不推进序列（COALESCE(NULLIF(:id,0), nextval(...))）。
 */
@Repository
class ArkLevelRepository(
    private val jdbi: Jdbi,
) {

    interface ArkLevelDao {
        @SqlUpdate(
            """
            INSERT INTO ark_level (level_id, stage_id, sha, cat_one, cat_two, cat_three, name, width, height, is_open, close_time, updated_at)
            VALUES (:levelId, :stageId, :sha, :catOne, :catTwo, :catThree, :name, :width, :height, :isOpen, :closeTime, :updatedAt)
            """,
        )
        @GetGeneratedKeys("id")
        @AllowUnusedBindings
        fun insert(@BindKotlin entity: ArkLevelEntity): Long

        /** 显式指定 id 的插入（基线：save 对 id 非 0 且不存在时按给定值插入，不推进序列）。 */
        @SqlUpdate(
            """
            INSERT INTO ark_level (id, level_id, stage_id, sha, cat_one, cat_two, cat_three, name, width, height, is_open, close_time, updated_at)
            VALUES (:id, :levelId, :stageId, :sha, :catOne, :catTwo, :catThree, :name, :width, :height, :isOpen, :closeTime, :updatedAt)
            """,
        )
        @GetGeneratedKeys("id")
        fun insertWithId(@BindKotlin entity: ArkLevelEntity): Long

        /**
         * 批量 save：id == 0 时由序列自增插入（COALESCE(NULLIF(:id,0), nextval(...))），
         * id != 0 时显式插入或 ON CONFLICT (id) DO UPDATE 全列覆盖——由数据库决定插入或更新。
         * 返回各行 id（与 entities 顺序一致），供调用方回填自增 id。
         * 新增字段只需在此 SQL 加列，无需改 Kotlin 绑定代码。
         *
         * `updated_at` 只在 INSERT 列清单里：DO UPDATE SET 里刻意不列它。开放状态跑批
         * （ArkLevelService.updateLevelsOfTypeInBatch）读整页 → 改 is_open → 全列 upsert 写回，
         * 若把该列放进 SET，每轮都会把整批活动关卡的 updated_at 续期，lite 变体的窗口判据直接失效。
         */
        @SqlBatch(
            """
            INSERT INTO ark_level (id, level_id, stage_id, sha, cat_one, cat_two, cat_three, name, width, height, is_open, close_time, updated_at)
            VALUES (COALESCE(NULLIF(:id, 0), nextval(pg_get_serial_sequence('ark_level', 'id'))),
                    :levelId, :stageId, :sha, :catOne, :catTwo, :catThree, :name, :width, :height, :isOpen, :closeTime, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                level_id = EXCLUDED.level_id,
                stage_id = EXCLUDED.stage_id,
                sha = EXCLUDED.sha,
                cat_one = EXCLUDED.cat_one,
                cat_two = EXCLUDED.cat_two,
                cat_three = EXCLUDED.cat_three,
                name = EXCLUDED.name,
                width = EXCLUDED.width,
                height = EXCLUDED.height,
                is_open = EXCLUDED.is_open,
                close_time = EXCLUDED.close_time
            """,
        )
        @GetGeneratedKeys("id")
        fun saveAll(@BindKotlin entities: List<ArkLevelEntity>): List<Long>
    }

    data class ShaProjection(val sha: String)

    private val dao: ArkLevelDao = jdbi.onDemand(ArkLevelDao::class.java)

    fun findByStageId(stageId: String): ArkLevelEntity? {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE stage_id = :stageId LIMIT 1")
                .bind("stageId", stageId)
                .mapTo<ArkLevelEntity>()
                .findFirst()
                .orElse(null)
        }
    }

    fun findAllByStageIds(stageIds: List<String>): List<ArkLevelEntity> {
        if (stageIds.isEmpty()) {
            return emptyList()
        }
        val placeholders = stageIds.mapIndexed { index, _ -> ":s$index" }.joinToString(", ")
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE stage_id IN ($placeholders)")
                .also { query -> stageIds.forEachIndexed { index, value -> query.bind("s$index", value) } }
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    fun findByLevelId(levelId: String): ArkLevelEntity? {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE level_id = :levelId LIMIT 1")
                .bind("levelId", levelId)
                .mapTo<ArkLevelEntity>()
                .findFirst()
                .orElse(null)
        }
    }

    fun findAllOpenLevels(): List<ArkLevelEntity> {
        // 基线语义：is_open = TRUE（= TRUE 不匹配 NULL）
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE is_open = TRUE")
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    fun insertEntity(entity: ArkLevelEntity): ArkLevelEntity {
        val generatedId = if (entity.id == 0L) {
            dao.insert(entity)
        } else {
            dao.insertWithId(entity)
        }
        entity.id = generatedId
        return entity
    }

    fun findById(id: Any): ArkLevelEntity? {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE id = :id LIMIT 1")
                .bind("id", id as Long)
                .mapTo<ArkLevelEntity>()
                .findFirst()
                .orElse(null)
        }
    }

    fun deleteById(id: Any): Boolean {
        val deleted = jdbi.withHandleUnchecked { handle ->
            handle.createUpdate("DELETE FROM ark_level WHERE id = :id")
                .bind("id", id as Long)
                .execute()
        }
        return deleted > 0
    }

    fun existsById(id: Any): Boolean {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT COUNT(*) FROM ark_level WHERE id = :id")
                .bind("id", id as Long)
                .mapTo(Long::class.java)
                .one() > 0L
        }
    }

    fun save(entity: ArkLevelEntity): ArkLevelEntity {
        // 单条 upsert：复用 saveAll 的 INSERT ... ON CONFLICT (id) DO UPDATE 全列覆盖，
        // 省去 existsById 预探（与 saveAll 一致，由数据库决定插入或更新）。
        saveAll(listOf(entity))
        return entity
    }

    fun findByLevelIdFuzzy(levelId: String): List<ArkLevelEntity> {
        // 基线语义：LIKE '%' || ? || '%'，无转义（%/_ 按通配符解释，测试记录基线）
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE level_id LIKE :levelId")
                .bind("levelId", "%$levelId%")
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    fun queryLevelByKeyword(keyword: String): List<ArkLevelEntity> {
        val pattern = "%$keyword%"
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT * FROM ark_level
                WHERE name LIKE :name OR level_id LIKE :levelId OR stage_id LIKE :stageId
                """.trimIndent(),
            )
                .bind("name", pattern)
                .bind("levelId", pattern)
                .bind("stageId", pattern)
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    fun findAllShaBy(): List<ShaProjection> {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT sha FROM ark_level")
                .mapTo(ShaProjection::class.java)
                .list()
        }
    }

    fun findAllByCatOne(catOne: String, pageable: Pageable): Page<ArkLevelEntity> {
        val total = jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT COUNT(*) FROM ark_level WHERE cat_one = :catOne")
                .bind("catOne", catOne)
                .mapTo(Long::class.java)
                .one()
        }
        val items = jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE cat_one = :catOne ORDER BY id LIMIT :limit OFFSET :offset")
                .bind("catOne", catOne)
                .bind("limit", pageable.pageSize)
                .bind("offset", pageable.offset)
                .mapTo<ArkLevelEntity>()
                .list()
        }
        return PageImpl(items, pageable, total)
    }

    fun saveAll(entities: List<ArkLevelEntity>) {
        // 批量 save：一次 @SqlBatch 由数据库决定插入或更新（ON CONFLICT (id) DO UPDATE），
        // 不再逐条 exists 检查（docs/ktorm-bugs-found.md #1 的 O(2N)）。
        // id == 0 由序列自增插入并回填；新增字段只需在 dao.saveAll 的 SQL 中加列。
        if (entities.isEmpty()) return
        val keys = dao.saveAll(entities)
        entities.forEachIndexed { i, entity -> entity.id = keys[i] }
    }

    /**
     * 统计指定分类下 cat_two 为空（NULL 或空串）的行数，供活动名回填任务做廉价门禁。
     */
    fun countBlankCatTwoByCatOne(catOne: String): Long {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                "SELECT COUNT(*) FROM ark_level WHERE cat_one = :catOne AND (cat_two IS NULL OR cat_two = '')",
            )
                .bind("catOne", catOne)
                .mapTo(Long::class.java)
                .one()
        }
    }

    /**
     * 查询指定分类下 cat_two 为空（NULL 或空串）的行。
     *
     * 注意：「活动关卡」以外的分类不得用查询结果的 cat_three 重建地图数据——只有活动关卡满足
     * `cat_three == 地图文件的 code`（其它分类的 parser 会覆写 cat_three，详见回填方案 §6.1）。
     */
    fun findAllBlankCatTwoByCatOne(catOne: String): List<ArkLevelEntity> {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT * FROM ark_level
                WHERE cat_one = :catOne AND (cat_two IS NULL OR cat_two = '')
                ORDER BY id
                """.trimIndent(),
            )
                .bind("catOne", catOne)
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    /**
     * 批量：仅在 cat_two 仍为空（NULL 或空串）时定向写入，不触碰其它列。
     *
     * 不能用 [saveAll]（全列 upsert）代替：回填与开放状态跑批可能并发，全列覆盖会把并发写入的
     * is_open / close_time 回退成读到的旧值。
     *
     * 条件（`cat_two IS NULL OR cat_two = ''`）是必要的：多个回填执行可能重叠（三个触发点由互相
     * 独立的标志守卫），若无条件写入，后到者会用更旧快照解析出的名字覆盖已填好的值——而回填只
     * 查询空值行，被覆盖的错误名字不会自愈。条件更新相当于一次 DB 层的 compare-and-set，
     * 后端多副本部署时同样成立。
     *
     * 单个 [PreparedBatch] 完成全部写入，避免逐行一次 DB 往返（首次回填可达近千行）。
     *
     * @param updates id 与要写入的活动名
     * @return 实际受影响行数；与 [updates] 的差额即竞争失败（目标行已被并发写入填好或不存在）
     */
    fun updateCatTwoByIds(updates: List<Pair<Long, String>>): Int {
        if (updates.isEmpty()) return 0
        return jdbi.withHandleUnchecked { handle ->
            val batch = handle.prepareBatch(
                """
                UPDATE ark_level SET cat_two = :catTwo
                WHERE id = :id AND (cat_two IS NULL OR cat_two = '')
                """.trimIndent(),
            )
            updates.forEach { (id, catTwo) ->
                batch.bind("catTwo", catTwo).bind("id", id).add()
            }
            batch.execute().sum()
        }
    }

    /**
     * 全量关卡查询，顺序为 `stage_id, id`。
     *
     * `/arknights/level/v2` 的版本摘要按查询结果顺序拼接，故必须有**确定性**顺序：
     * 无 ORDER BY 时 PG 的返回顺序随执行计划变化，同一份数据会算出两个版本号，
     * 让客户端把所有内容误判为「已更新」并重新下载。
     */
    fun findAllOrdered(): List<ArkLevelEntity> {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level ORDER BY stage_id, id")
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    /**
     * 指定分类下在 [since] 之后同步进来的行（`/arknights/level/v2` 的 lite 变体）。
     *
     * 用 `>=`：边界值算窗口内，与方案 §4 的判据一致。
     * `updated_at IS NULL`（存量行尚未回填）的行不会被命中——宁可少返，不可把老数据当新数据。
     */
    fun findAllUpdatedSince(catOne: String, since: LocalDateTime): List<ArkLevelEntity> {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                SELECT * FROM ark_level
                WHERE cat_one = :catOne AND updated_at >= :since
                ORDER BY stage_id, id
                """.trimIndent(),
            )
                .bind("catOne", catOne)
                .bind("since", since)
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    /**
     * `updated_at` 仍为 NULL 的行数（存量回填的廉价门禁：0 行即表示无需触网）。
     */
    fun countNullUpdatedAt(): Long {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT COUNT(*) FROM ark_level WHERE updated_at IS NULL")
                .mapTo(Long::class.java)
                .one()
        }
    }

    /** `updated_at` 仍为 NULL 的行，按 id 升序（存量回填的输入）。 */
    fun findAllNullUpdatedAt(): List<ArkLevelEntity> {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level WHERE updated_at IS NULL ORDER BY id")
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    /**
     * 批量回填 `updated_at`，**仅对仍为 NULL 的行生效**。
     *
     * 条件（`updated_at IS NULL`）是必要的：回填与同步任务可能并发，轮询期间新同步进来的行
     * 已由 INSERT 写入了真实时刻，若不设条件就会被回填覆盖成「窗口内/窗口外」的判定值。
     * 语义与 [updateCatTwoByIds] 的条件更新一致，相当于一次 DB 层的 compare-and-set。
     *
     * @param updates id 与要写入的时刻
     * @return 实际受影响行数；与 [updates] 的差额即已被 INSERT 抢先写入 N 行
     */
    fun updateUpdatedAtByIds(updates: List<Pair<Long, LocalDateTime>>): Int {
        if (updates.isEmpty()) return 0
        return jdbi.withHandleUnchecked { handle ->
            val batch = handle.prepareBatch(
                """
                UPDATE ark_level SET updated_at = :updatedAt
                WHERE id = :id AND updated_at IS NULL
                """.trimIndent(),
            )
            updates.forEach { (id, updatedAt) ->
                batch.bind("updatedAt", updatedAt).bind("id", id).add()
            }
            batch.execute().sum()
        }
    }

    fun findAll(): List<ArkLevelEntity> {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT * FROM ark_level")
                .mapTo<ArkLevelEntity>()
                .list()
        }
    }

    fun count(): Long {
        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT COUNT(*) FROM ark_level")
                .mapTo(Long::class.java)
                .one()
        }
    }
}
