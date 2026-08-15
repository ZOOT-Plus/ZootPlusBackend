package plus.maa.backend.repository.ktorm

import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.mapper.ColumnMapperFactory
import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.freemarker.UseFreemarkerEngine
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings
import org.jdbi.v3.sqlobject.customizer.Bind
import org.jdbi.v3.sqlobject.customizer.Define
import org.jdbi.v3.sqlobject.kotlin.BindKotlin
import org.jdbi.v3.sqlobject.kotlin.RegisterKotlinMapper
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys
import org.jdbi.v3.sqlobject.statement.SqlQuery
import org.jdbi.v3.sqlobject.statement.SqlUpdate
import org.postgresql.util.PGobject
import org.springframework.stereotype.Repository
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.repository.entity.CopilotSetEntity
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.sql.ResultSet
import java.sql.Types
import java.util.Optional

/**
 * jsonb 数组（copilot_ids 列）绑定：`List<Long>` → PGobject(type=json)。
 *
 * @BindKotlin 按属性声明的类型（`List<Long>`）查找 ArgumentFactory，故用
 * [AbstractArgumentFactory] 的泛型参数匹配；读写格式为 defaultJson.encodeToString(List<Long>)。
 *
 * 通过 DAO 接口上的 [RegisterArgumentFactory] 注册，仅对本模块 DAO 生效。
 */
class CopilotIdsArgumentFactory : AbstractArgumentFactory<List<Long>>(Types.OTHER) {
    override fun build(value: List<Long>, config: ConfigRegistry): Argument {
        return Argument { position, statement, _ ->
            val pgObject = PGobject().apply {
                type = "json"
                this.value = defaultJson.encodeToString(value)
            }
            statement.setObject(position, pgObject)
        }
    }
}

/**
 * jsonb 数组列（copilot_ids）读回：`List<Long>` 属性默认被 jdbi 当作 PG 数组列映射
 * （CollectorColumnMapper → PgArray 解码，jsonb 列上抛 ArrayIndexOutOfBoundsException），
 * 这里用 `rs.getString` + defaultJson 解码覆盖。
 *
 * 在 [CopilotSetRepository] init 时注册到注入的 Jdbi 实例，DAO 与 querySets 的
 * 手写 SQL 映射均生效。
 */
class CopilotIdsColumnMapper : ColumnMapper<List<Long>> {
    override fun map(rs: ResultSet, columnNumber: Int, ctx: StatementContext): List<Long> {
        val raw = rs.getString(columnNumber) ?: return emptyList()
        return defaultJson.decodeFromString<List<Long>>(raw)
    }
}

/**
 * [CopilotIdsColumnMapper] 的工厂注册：Kotlin 反射对 `List<Long>` 会给出精确
 * （`java.util.List<java.lang.Long>`）与协变（`List<? extends Long>`）两种 Java 类型形态，
 * 两者不相等，需显式匹配两种形态；注册到注入的 Jdbi 实例（DAO 与 querySets 手写 SQL 均生效）。
 */
class CopilotIdsColumnMapperFactory : ColumnMapperFactory {
    override fun build(type: java.lang.reflect.Type, config: ConfigRegistry): Optional<ColumnMapper<*>> {
        val t = type
        if (t is ParameterizedType && t.rawType == List::class.java) {
            val elementType = t.actualTypeArguments.firstOrNull() ?: return Optional.empty()
            val elementClass = when (elementType) {
                is Class<*> -> elementType
                is WildcardType -> elementType.upperBounds.firstOrNull() as? Class<*>
                else -> null
            }
            if (elementClass == Long::class.javaObjectType) {
                return Optional.of(CopilotIdsColumnMapper())
            }
        }
        return Optional.empty()
    }
}

@RegisterArgumentFactory(CopilotIdsArgumentFactory::class)
interface CopilotSetDao {

    @SqlQuery(
        """
        SELECT id, name, description, copilot_ids, views, hot_score, creator_id,
               create_time, update_time, status, "delete"
        FROM copilot_set
        WHERE id = :id
        """,
    )
    @RegisterKotlinMapper(CopilotSetEntity::class)
    fun findById(id: Long): CopilotSetEntity?

    @SqlUpdate("DELETE FROM copilot_set WHERE id = :id")
    fun deleteById(id: Long): Int

    @SqlQuery("SELECT COUNT(*) FROM copilot_set WHERE id = :id")
    fun existsById(id: Long): Long

    @SqlUpdate(
        """
        INSERT INTO copilot_set (name, description, copilot_ids, views, hot_score, creator_id,
                                 create_time, update_time, status, "delete")
        VALUES (:name, :description, :copilotIds, :views, :hotScore, :creatorId,
                :createTime, :updateTime, :status, :delete)
        """,
    )
    @GetGeneratedKeys("id")
    @AllowUnusedBindings
    fun insert(@BindKotlin entity: CopilotSetEntity): Long

    /** save() 中"显式指定 id 且 DB 中不存在"的插入路径（id 列一并写入，不回推序列，保持 ktorm 基线）。 */
    @SqlUpdate(
        """
        INSERT INTO copilot_set (id, name, description, copilot_ids, views, hot_score, creator_id,
                                 create_time, update_time, status, "delete")
        VALUES (:id, :name, :description, :copilotIds, :views, :hotScore, :creatorId,
                :createTime, :updateTime, :status, :delete)
        """,
    )
    fun insertWithExplicitId(@BindKotlin entity: CopilotSetEntity)

    /**
     * 全列 SET 更新（本项目更新路径均为"读出→改→写回"，未改列写入原值，行为等价）。
     */
    @SqlUpdate(
        """
        UPDATE copilot_set
        SET name = :name, description = :description, copilot_ids = :copilotIds,
            views = :views, hot_score = :hotScore, creator_id = :creatorId,
            create_time = :createTime, update_time = :updateTime,
            status = :status, "delete" = :delete
        WHERE id = :id
        """,
    )
    fun update(@BindKotlin entity: CopilotSetEntity)

    @SqlUpdate("UPDATE copilot_set SET views = views + 1 WHERE id = :id")
    fun incrViews(id: Long)

    @SqlQuery(
        """
        SELECT id, name, description, copilot_ids, views, hot_score, creator_id,
               create_time, update_time, status, "delete"
        FROM copilot_set
        """,
    )
    @RegisterKotlinMapper(CopilotSetEntity::class)
    fun findAll(): List<CopilotSetEntity>

    @SqlQuery("SELECT COUNT(*) FROM copilot_set")
    fun countAll(): Long

    @SqlQuery("SELECT COUNT(*) FROM copilot_set WHERE \"delete\" = false")
    fun countNotDeleted(): Long

    /**
     * 分页取未删除作业集。必须带 ORDER BY id：无排序的 LIMIT/OFFSET 在页间发生 UPDATE 后行序漂移，
     * 导致重复/遗漏行（CopilotSetScoreRefreshTask 分页循环踩坑）。
     */
    @SqlQuery(
        """
        SELECT id, name, description, copilot_ids, views, hot_score, creator_id,
               create_time, update_time, status, "delete"
        FROM copilot_set
        WHERE "delete" = false
        ORDER BY id
        LIMIT :limit OFFSET :offset
        """,
    )
    @RegisterKotlinMapper(CopilotSetEntity::class)
    fun findNotDeletedPage(offset: Int, limit: Int): List<CopilotSetEntity>

    /**
     * 作业集分页查询（动态条件）。
     *
     * @param copilotIdsJson copilotIds 非空时为其 JSON 数组文本（调用方编码，绑定 `@> ?::jsonb`）；
     *   copilotIds 为 null 时传空串（模板不引用，被 [AllowUnusedBindings] 容忍）
     */
    @SqlQuery(
        """
        SELECT cs.id, cs.name, cs.description, cs.copilot_ids, cs.views, cs.hot_score,
               cs.creator_id, cs.create_time, cs.update_time, cs.status, cs."delete"
        FROM copilot_set cs
        WHERE cs."delete" = false
        <#if userId??>
          AND (cs.status = 'PUBLIC' OR cs.creator_id = :userId)
        <#else>
          AND cs.status = 'PUBLIC'
        </#if>
        <#if onlyFollowing>
          AND cs.creator_id IN (SELECT follow_user_id FROM user_follow WHERE user_id = :userId)
        </#if>
        <#if creatorId??>
          AND cs.creator_id = :creatorId
        </#if>
        <#if keyword??>
          AND (cs.name LIKE :keyword OR cs.description LIKE :keyword)
        </#if>
        <#if copilotIds??>
          AND cs.copilot_ids @> :copilotIdsJson::jsonb
        </#if>
        ORDER BY cs.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    @UseFreemarkerEngine
    @AllowUnusedBindings
    @RegisterKotlinMapper(CopilotSetEntity::class)
    fun querySets(
        @Define("userId") @Bind("userId") userId: Long?,
        @Define("onlyFollowing") onlyFollowing: Boolean,
        @Define("creatorId") @Bind("creatorId") creatorId: Long?,
        @Define("keyword") @Bind("keyword") keyword: String?,
        @Define("copilotIds") copilotIds: Set<Long>?,
        @Bind("copilotIdsJson") copilotIdsJson: String,
        @Bind("limit") limit: Int,
        @Bind("offset") offset: Int,
    ): List<CopilotSetEntity>

    /** 与 [querySets] 相同的动态条件，取总数（querySets 分页用）。 */
    @SqlQuery(
        """
        SELECT COUNT(*) FROM copilot_set cs
        WHERE cs."delete" = false
        <#if userId??>
          AND (cs.status = 'PUBLIC' OR cs.creator_id = :userId)
        <#else>
          AND cs.status = 'PUBLIC'
        </#if>
        <#if onlyFollowing>
          AND cs.creator_id IN (SELECT follow_user_id FROM user_follow WHERE user_id = :userId)
        </#if>
        <#if creatorId??>
          AND cs.creator_id = :creatorId
        </#if>
        <#if keyword??>
          AND (cs.name LIKE :keyword OR cs.description LIKE :keyword)
        </#if>
        <#if copilotIds??>
          AND cs.copilot_ids @> :copilotIdsJson::jsonb
        </#if>
        """,
    )
    @UseFreemarkerEngine
    @AllowUnusedBindings
    fun countSets(
        @Define("userId") @Bind("userId") userId: Long?,
        @Define("onlyFollowing") onlyFollowing: Boolean,
        @Define("creatorId") @Bind("creatorId") creatorId: Long?,
        @Define("keyword") @Bind("keyword") keyword: String?,
        @Define("copilotIds") copilotIds: Set<Long>?,
        @Bind("copilotIdsJson") copilotIdsJson: String,
    ): Long
}

@Repository
class CopilotSetRepository(
    private val jdbi: Jdbi,
) {
    init {
        // jsonb 列映射注册：见 CopilotIdsColumnMapperFactory 说明
        jdbi.registerColumnMapper(CopilotIdsColumnMapperFactory())
    }

    private val dao: CopilotSetDao = jdbi.onDemand(CopilotSetDao::class.java)

    fun findById(id: Any): CopilotSetEntity? = dao.findById(id as Long)

    fun deleteById(id: Any): Boolean = dao.deleteById(id as Long) > 0

    fun existsById(id: Any): Boolean = dao.existsById(id as Long) > 0

    /**
     * 插入并回填自增 id（原地写入传入实体，保持 ktorm `add` 的回填语义，
     * 服务层 `create()` 依赖 `entity.id` 读取）。
     */
    fun insertEntity(entity: CopilotSetEntity): CopilotSetEntity {
        entity.id = dao.insert(entity)
        return entity
    }

    fun updateEntity(entity: CopilotSetEntity): CopilotSetEntity {
        dao.update(entity)
        return entity
    }

    fun findByIdAsOptional(id: Long): Optional<CopilotSetEntity> {
        return findById(id)?.let { Optional.of(it) } ?: Optional.empty()
    }

    /**
     * isNewEntity = (id == 0L || DB 中不存在该 id)；与 ktorm 基类语义一致。
     * 显式指定 id 且不存在时走 [CopilotSetDao.insertWithExplicitId]（id 列一并写入，序列不回推）。
     */
    fun save(entity: CopilotSetEntity): CopilotSetEntity {
        return if (isNewEntity(entity)) {
            if (entity.id == 0L) {
                insertEntity(entity)
            } else {
                dao.insertWithExplicitId(entity)
                entity
            }
        } else {
            updateEntity(entity)
        }
    }

    fun incrViews(id: Long) {
        dao.incrViews(id)
    }

    fun findAll(): List<CopilotSetEntity> = dao.findAll()

    fun count(): Long = dao.countAll()

    /** 未删除作业集总数（CopilotSetScoreRefreshTask 用）。 */
    fun countNotDeleted(): Long = dao.countNotDeleted()

    /** 分页取未删除作业集（CopilotSetScoreRefreshTask 用，按 id 排序）。 */
    fun findNotDeletedPage(offset: Int, limit: Int): List<CopilotSetEntity> = dao.findNotDeletedPage(offset, limit)

    /** 批量写 hot_score；空 map no-op。 */
    fun batchUpdateHotScores(scoreMap: Map<Long, Double>) {
        if (scoreMap.isEmpty()) return
        jdbi.useHandle<Exception> { handle ->
            val batch = handle.prepareBatch("UPDATE copilot_set SET hot_score = :score WHERE id = :id")
            scoreMap.forEach { (id, score) ->
                batch.bind("id", id).bind("score", score).add()
            }
            batch.execute()
        }
    }

    /**
     * 作业集分页查询。
     *
     * 动态条件委托 [CopilotSetDao.querySets] / [CopilotSetDao.countSets]。
     *
     * @param copilotIds 非空时以 defaultJson 编码为 JSON 数组字符串绑定 `@> ?::jsonb`（调用方保证已去重、非空）
     * @return (当前页列表, 总数)；hasNext/totalPages 由调用方按 (offset+limit)<total 计算
     */
    fun querySets(
        userId: Long?,
        onlyFollowing: Boolean,
        creatorId: Long?,
        keyword: String?,
        copilotIds: Set<Long>?,
        offset: Int,
        limit: Int,
    ): Pair<List<CopilotSetEntity>, Long> {
        val copilotIdsJson = copilotIds?.let { defaultJson.encodeToString(it) } ?: ""
        val sets = dao.querySets(userId, onlyFollowing, creatorId, keyword, copilotIds, copilotIdsJson, limit, offset)
        val total = dao.countSets(userId, onlyFollowing, creatorId, keyword, copilotIds, copilotIdsJson)
        return sets to total
    }

    private fun isNewEntity(entity: CopilotSetEntity): Boolean {
        return entity.id == 0L || !existsById(entity.id)
    }
}
