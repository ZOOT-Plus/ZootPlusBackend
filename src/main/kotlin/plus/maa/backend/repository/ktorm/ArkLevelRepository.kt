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
            INSERT INTO ark_level (level_id, stage_id, sha, cat_one, cat_two, cat_three, name, width, height, is_open, close_time)
            VALUES (:levelId, :stageId, :sha, :catOne, :catTwo, :catThree, :name, :width, :height, :isOpen, :closeTime)
            """,
        )
        @GetGeneratedKeys("id")
        @AllowUnusedBindings
        fun insert(@BindKotlin entity: ArkLevelEntity): Long

        /** 显式指定 id 的插入（基线：save 对 id 非 0 且不存在时按给定值插入，不推进序列）。 */
        @SqlUpdate(
            """
            INSERT INTO ark_level (id, level_id, stage_id, sha, cat_one, cat_two, cat_three, name, width, height, is_open, close_time)
            VALUES (:id, :levelId, :stageId, :sha, :catOne, :catTwo, :catThree, :name, :width, :height, :isOpen, :closeTime)
            """,
        )
        @GetGeneratedKeys("id")
        fun insertWithId(@BindKotlin entity: ArkLevelEntity): Long

        /**
         * 批量 save：id == 0 时由序列自增插入（COALESCE(NULLIF(:id,0), nextval(...))），
         * id != 0 时显式插入或 ON CONFLICT (id) DO UPDATE 全列覆盖——由数据库决定插入或更新。
         * 返回各行 id（与 entities 顺序一致），供调用方回填自增 id。
         * 新增字段只需在此 SQL 加列，无需改 Kotlin 绑定代码。
         */
        @SqlBatch(
            """
            INSERT INTO ark_level (id, level_id, stage_id, sha, cat_one, cat_two, cat_three, name, width, height, is_open, close_time)
            VALUES (COALESCE(NULLIF(:id, 0), nextval(pg_get_serial_sequence('ark_level', 'id'))),
                    :levelId, :stageId, :sha, :catOne, :catTwo, :catThree, :name, :width, :height, :isOpen, :closeTime)
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
