package plus.maa.backend.service

import org.springframework.stereotype.Service
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

@Service
class RatingService(private val ratingRepository: RatingRepository) {
    /**
     * Update rating of target object
     *
     * @param keyType Target key type
     * @param key Key
     * @param raterId Rater's ID
     * @param ratingType Target rating type
     * @return A pair, previous one and the target one.
     */
    fun rate(keyType: Rating.KeyType, key: String, raterId: String, ratingType: RatingType): Pair<RatingEntity, RatingEntity> {
        // 先查命中路径（常见：用户已有评分记录），未命中再走原子“插入或获取”。
        // insertOrGet 内部用 INSERT ... ON CONFLICT DO NOTHING + 重读，消除并发首次评分时
        // find-then-insert 竞态导致后到者撞 idx_rating_unique 抛 DuplicateKeyException（未捕获 → 500）。
        val rating = ratingRepository.findByTypeAndKeyAndUserId(
            keyType,
            key,
            raterId,
        ) ?: ratingRepository.insertOrGet(
            RatingEntity(
                type = keyType,
                key = key,
                userId = raterId,
                rating = RatingType.NONE,
                rateTime = LocalDateTime.now(),
            ),
        )

        if (ratingType == rating.rating) return rating to rating

        val prevRating = rating.rating
        rating.rating = ratingType
        rating.rateTime = LocalDateTime.now()
        ratingRepository.updateEntity(rating)

        // 创建一个表示之前状态的对象
        val prevEntity = RatingEntity(
            id = rating.id,
            type = rating.type,
            key = rating.key,
            userId = rating.userId,
            rating = prevRating,
            rateTime = rating.rateTime,
        )

        return prevEntity to rating
    }

    /**
     * Calculate like/dislike counts from rating change.
     * @param ratingChange Pair of previous rating and current rating
     * @return Pair of like count change and dislike count change
     */
    fun calcLikeChange(ratingChange: Pair<RatingEntity, RatingEntity>): Pair<Long, Long> {
        val (prev, next) = ratingChange
        val likeCountChange = next.rating.countLike() - prev.rating.countLike()
        val dislikeCountChange = next.rating.countDislike() - prev.rating.countDislike()
        return likeCountChange to dislikeCountChange
    }

    fun rateComment(commentId: Long, raterId: String, ratingType: RatingType): Pair<RatingEntity, RatingEntity> =
        rate(Rating.KeyType.COMMENT, commentId.toString(), raterId, ratingType)

    fun rateCopilot(copilotId: Long, raterId: String, ratingType: RatingType): Pair<RatingEntity, RatingEntity> =
        rate(Rating.KeyType.COPILOT, copilotId.toString(), raterId, ratingType)

    fun findPersonalRatingOfCopilot(raterId: String, copilotId: Long): RatingEntity? =
        ratingRepository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, copilotId.toString(), raterId)
}
