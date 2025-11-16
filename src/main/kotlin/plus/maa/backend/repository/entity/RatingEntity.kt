package plus.maa.backend.repository.entity

import org.ktorm.entity.Entity
import org.ktorm.schema.Table
import org.ktorm.schema.datetime
import org.ktorm.schema.enum
import org.ktorm.schema.long
import org.ktorm.schema.varchar
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

interface RatingEntity : Entity<RatingEntity> {
    var id: Long
    var type: Rating.KeyType
    var key: String
    var userId: String
    var rating: RatingType
    var rateTime: LocalDateTime

    companion object : Entity.Factory<RatingEntity>()
}

object Ratings : Table<RatingEntity>("rating") {
    val id = long("id").primaryKey().bindTo { it.id }
    val type = enum<Rating.KeyType>("type").bindTo { it.type }
    val key = varchar("key").bindTo { it.key }
    val userId = varchar("user_id").bindTo { it.userId }
    val rating = enum<RatingType>("rating").bindTo { it.rating }
    val rateTime = datetime("rate_time").bindTo { it.rateTime }
}
