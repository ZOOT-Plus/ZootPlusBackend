package plus.maa.backend.repository.entity

import kotlinx.serialization.Transient
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

data class RatingEntity(
    var id: Long = 0,
    var type: Rating.KeyType,
    var key: String,
    var userId: String,
    var rating: RatingType,
    var rateTime: LocalDateTime,
) {
    /** 加载时的字段快照；null 表示实体并非从 DB 加载（工厂构造），此时视为全列脏。 */
    @Transient
    internal var snapshot: RatingEntity? = null
        private set

    /** 记录当前字段快照（DB 加载/写入成功后调用），返回自身便于链式调用。 */
    internal fun attachSnapshot(): RatingEntity {
        snapshot = copy()
        return this
    }

    /** 更新成功后刷新快照。 */
    internal fun refreshSnapshot() {
        snapshot = copy()
    }

    /**
     * 与快照对比，返回需要更新的列名（规范顺序）；无快照时返回全列。
     * id 是主键，永不进入 SET 列表。
     */
    internal fun dirtyColumns(): List<String> {
        val snap = snapshot ?: return listOf("type", "key", "user_id", "rating", "rate_time")
        return buildList {
            if (type != snap.type) add("type")
            if (key != snap.key) add("key")
            if (userId != snap.userId) add("user_id")
            if (rating != snap.rating) add("rating")
            if (rateTime != snap.rateTime) add("rate_time")
        }
    }
}
