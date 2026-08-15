package plus.maa.backend.repository.ktorm

import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings
import org.jdbi.v3.sqlobject.customizer.Bind
import org.jdbi.v3.sqlobject.customizer.BindList
import org.jdbi.v3.sqlobject.kotlin.BindKotlin
import org.jdbi.v3.sqlobject.kotlin.RegisterKotlinMapper
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys
import org.jdbi.v3.sqlobject.statement.SqlQuery
import org.jdbi.v3.sqlobject.statement.SqlUpdate
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.service.model.RatingCount
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

@Repository
class RatingRepository(
    private val jdbi: Jdbi,
) {

    private val dao: RatingDao = jdbi.onDemand(RatingDao::class.java)

    fun findByTypeAndKeyAndUserId(type: Rating.KeyType, key: String, userId: String): RatingEntity? =
        dao.findByTypeAndKeyAndUserId(type, key, userId)?.attachSnapshot()

    /** 获取指定时间（严格大于）之后的评分统计，按 key 分组。 */
    fun getRatingCountAfter(after: LocalDateTime): List<RatingCount> = dao.getRatingCountAfter(after)

    /** 获取所有评分统计，按 key 分组。 */
    fun getAllRatingCount(): List<RatingCount> = dao.getAllRatingCount()

    /**
     * `SELECT key, COUNT(id) FROM rating WHERE type=? AND key IN (...) AND rating=? AND rate_time >= ? GROUP BY key`。
     * 空 keys 直接返回空列表（显式守卫，避免空 `IN ()` 语法错误）。
     */
    fun countByTypeKeyInRatingAfter(
        type: Rating.KeyType,
        keys: Collection<String>,
        rating: RatingType,
        startTime: LocalDateTime,
    ): List<RatingCount> {
        if (keys.isEmpty()) return emptyList()
        return dao.countByTypeKeyInRatingAfter(type, keys, rating, startTime)
    }

    fun findById(id: Any): RatingEntity? = dao.findById(id as Long)?.attachSnapshot()

    fun deleteById(id: Any): Boolean = dao.deleteById(id as Long) > 0

    fun existsById(id: Any): Boolean = dao.existsById(id as Long) > 0

    fun count(): Long = dao.count()

    fun findAll(): List<RatingEntity> = dao.findAll().onEach { it.attachSnapshot() }

    fun insertEntity(entity: RatingEntity): RatingEntity {
        // id == 0：自增列不参与 INSERT，回填生成主键；id != 0：保留显式 id（基线 Ktorm add 全列插入语义）
        entity.id = if (entity.id == 0L) dao.insert(entity) else dao.insertWithExplicitId(entity)
        entity.refreshSnapshot()
        return entity
    }

    /**
     * 原子“插入或获取”：对 (type, key, user_id) 执行 INSERT ... ON CONFLICT DO NOTHING，
     * 随后按唯一三元组重读返回带 id 的权威行（附加快照）。
     *
     * 无论本次是新建还是并发请求已抢先写入，最终都返回同一行；从而消除
     * [RatingService.rate] 中 find-then-insert 竞态下后到者撞 idx_rating_unique 抛
     * DuplicateKeyException（未捕获 → 500）的问题。
     *
     * 注意：onDemand DAO 每次方法调用独立 handle，insert 与 select 不在同一事务，
     * 但均为自动提交，insert 在 select 前已可见，语义正确。
     */
    fun insertOrGet(entity: RatingEntity): RatingEntity {
        dao.insertOnConflictDoNothing(entity)
        return dao.findByTypeAndKeyAndUserId(entity.type, entity.key, entity.userId)
            ?.attachSnapshot()
            ?: error(
                "rating 行应在 INSERT ON CONFLICT 后存在: type=${entity.type}, key=${entity.key}, user_id=${entity.userId}",
            )
    }

    /**
     * 保持基线 flushChanges 脏检查语义：仅更新与加载快照不同的列（快照见 [RatingEntity.snapshot]）；
     * 无快照（工厂构造）的实体按全列 SET 处理。
     */
    fun updateEntity(entity: RatingEntity): RatingEntity {
        val dirty = entity.dirtyColumns()
        if (dirty.isEmpty()) return entity
        jdbi.useHandle<Exception> { handle ->
            val setClause = dirty.joinToString(", ") { "$it = :$it" }
            val update = handle
                .createUpdate("UPDATE rating SET $setClause WHERE id = :id")
                .bind("id", entity.id)
            for (column in dirty) {
                update.bind(column, entity.columnValue(column))
            }
            update.execute()
        }
        entity.refreshSnapshot()
        return entity
    }

    fun save(entity: RatingEntity): RatingEntity =
        if (entity.id == 0L || !existsById(entity.id)) insertEntity(entity) else updateEntity(entity)

    private fun RatingEntity.columnValue(column: String): Any? = when (column) {
        "type" -> type.name
        "key" -> key
        "user_id" -> userId
        "rating" -> rating.name
        "rate_time" -> rateTime
        else -> error("Unknown rating column: $column")
    }
}

/**
 * rating 表 SQL 访问接口（Jdbi SqlObject 运行时代理）。
 */
interface RatingDao {

    @SqlQuery(
        """
        SELECT id, type, key, user_id, rating, rate_time
        FROM rating
        WHERE type = :type AND key = :key AND user_id = :userId
        LIMIT 1
        """,
    )
    @RegisterKotlinMapper(RatingEntity::class)
    fun findByTypeAndKeyAndUserId(
        @Bind("type") type: Rating.KeyType,
        @Bind("key") key: String,
        @Bind("userId") userId: String,
    ): RatingEntity?

    @SqlQuery(
        """
        SELECT key, COUNT(id) AS count
        FROM rating
        WHERE rate_time > :after
        GROUP BY key
        """,
    )
    @RegisterKotlinMapper(RatingCount::class)
    fun getRatingCountAfter(@Bind("after") after: LocalDateTime): List<RatingCount>

    @SqlQuery(
        """
        SELECT key, COUNT(id) AS count
        FROM rating
        GROUP BY key
        """,
    )
    @RegisterKotlinMapper(RatingCount::class)
    fun getAllRatingCount(): List<RatingCount>

    @SqlQuery(
        """
        SELECT key, COUNT(id) AS count
        FROM rating
        WHERE type = :type AND key IN (<keys>) AND rating = :rating AND rate_time >= :startTime
        GROUP BY key
        """,
    )
    @RegisterKotlinMapper(RatingCount::class)
    fun countByTypeKeyInRatingAfter(
        @Bind("type") type: Rating.KeyType,
        @BindList("keys") keys: Collection<String>,
        @Bind("rating") rating: RatingType,
        @Bind("startTime") startTime: LocalDateTime,
    ): List<RatingCount>

    @SqlQuery(
        """
        SELECT id, type, key, user_id, rating, rate_time
        FROM rating
        WHERE id = :id
        """,
    )
    @RegisterKotlinMapper(RatingEntity::class)
    fun findById(@Bind("id") id: Long): RatingEntity?

    @SqlUpdate("DELETE FROM rating WHERE id = :id")
    fun deleteById(@Bind("id") id: Long): Int

    @SqlQuery("SELECT COUNT(*) FROM rating WHERE id = :id")
    fun existsById(@Bind("id") id: Long): Long

    @SqlQuery("SELECT COUNT(*) FROM rating")
    fun count(): Long

    /** 无排序（基线：Ktorm `entities.toList()` 无 ORDER BY）。 */
    @SqlQuery(
        """
        SELECT id, type, key, user_id, rating, rate_time
        FROM rating
        """,
    )
    @RegisterKotlinMapper(RatingEntity::class)
    fun findAll(): List<RatingEntity>

    @SqlUpdate(
        """
        INSERT INTO rating (type, key, user_id, rating, rate_time)
        VALUES (:type, :key, :userId, :rating, :rateTime)
        """,
    )
    @GetGeneratedKeys("id")
    @AllowUnusedBindings
    fun insert(@BindKotlin entity: RatingEntity): Long

    /**
     * 原子“插入或忽略”：对 (type, key, user_id) 执行 `INSERT ... ON CONFLICT DO NOTHING`，
     * 命中唯一索引 idx_rating_unique 时静默跳过（0 行）。调用方须随后重读以拿到带 id 的权威行。
     * 用于消除 RatingService.rate 的 find-then-insert 竞态。
     */
    @SqlUpdate(
        """
        INSERT INTO rating (type, key, user_id, rating, rate_time)
        VALUES (:type, :key, :userId, :rating, :rateTime)
        ON CONFLICT (type, key, user_id) DO NOTHING
        """,
    )
    @AllowUnusedBindings
    fun insertOnConflictDoNothing(@BindKotlin entity: RatingEntity): Int

    /**
     * save 语义：实体携带非零 id 且库中不存在该 id 时，按显式 id 插入（基线：Ktorm add 全列插入）。
     */
    @SqlUpdate(
        """
        INSERT INTO rating (id, type, key, user_id, rating, rate_time)
        VALUES (:id, :type, :key, :userId, :rating, :rateTime)
        """,
    )
    @GetGeneratedKeys("id")
    @AllowUnusedBindings
    fun insertWithExplicitId(@BindKotlin entity: RatingEntity): Long
}
