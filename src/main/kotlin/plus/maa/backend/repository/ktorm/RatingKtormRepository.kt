package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.dsl.and
import org.ktorm.dsl.count
import org.ktorm.dsl.eq
import org.ktorm.dsl.from
import org.ktorm.dsl.greater
import org.ktorm.dsl.groupBy
import org.ktorm.dsl.map
import org.ktorm.dsl.select
import org.ktorm.dsl.where
import org.ktorm.entity.add
import org.ktorm.entity.any
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.removeIf
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.repository.entity.Ratings
import plus.maa.backend.service.model.RatingCount
import java.time.LocalDateTime

@Repository
class RatingKtormRepository(
    database: Database,
) : KtormRepository<RatingEntity, Ratings>(database, Ratings) {

    fun findByTypeAndKeyAndUserId(type: Rating.KeyType, key: String, userId: String): RatingEntity? {
        return entities.firstOrNull {
            (it.type eq type) and (it.key eq key) and (it.userId eq userId)
        }
    }

    /**
     * 获取指定时间后的评分统计
     */
    fun getRatingCountAfter(after: LocalDateTime): List<RatingCount> {
        return database
            .from(Ratings)
            .select(Ratings.key, count(Ratings.id))
            .where { Ratings.rateTime greater after }
            .groupBy(Ratings.key)
            .map { row ->
                RatingCount(
                    key = row[Ratings.key]!!,
                    count = row.getLong(2), // second column
                )
            }
    }

    /**
     * 获取所有评分统计
     */
    fun getAllRatingCount(): List<RatingCount> {
        return database
            .from(Ratings)
            .select(Ratings.key, count(Ratings.id))
            .groupBy(Ratings.key)
            .map { row ->
                RatingCount(
                    key = row[Ratings.key]!!,
                    count = row.getLong(2), // second column
                )
            }
    }

    override fun findById(id: Any): RatingEntity? {
        return entities.firstOrNull { it.id eq id.toString() }
    }

    override fun deleteById(id: Any): Boolean {
        return entities.removeIf { it.id eq id.toString() } > 0
    }

    override fun existsById(id: Any): Boolean {
        return entities.any { it.id eq id.toString() }
    }

    override fun getIdColumn(entity: RatingEntity): Any = entity.id

    override fun isNewEntity(entity: RatingEntity): Boolean {
        return entity.id.isBlank() || !existsById(entity.id)
    }

    fun insertEntity(entity: RatingEntity): RatingEntity {
        entities.add(entity)
        return entity
    }

    fun updateEntity(entity: RatingEntity): RatingEntity {
        entity.flushChanges()
        return entity
    }
}
