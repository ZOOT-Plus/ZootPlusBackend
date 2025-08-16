package plus.maa.backend.service

import org.springframework.stereotype.Service
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.repository.entity.RatingEntity
import plus.maa.backend.repository.ktorm.RatingKtormRepository
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

@Service
class RatingService(private val ratingKtormRepository: RatingKtormRepository) {
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
        val rating = ratingKtormRepository.findByTypeAndKeyAndUserId(
            keyType,
            key,
            raterId,
        ) ?: run {
            val newRating = RatingEntity {
                this.id = ""
                this.type = keyType
                this.key = key
                this.userId = raterId
                this.rating = RatingType.NONE
                this.rateTime = LocalDateTime.now()
            }
            ratingKtormRepository.insertEntity(newRating)
            newRating
        }

        if (ratingType == rating.rating) return rating to rating

        val prevRating = rating.rating
        rating.rating = ratingType
        rating.rateTime = LocalDateTime.now()
        ratingKtormRepository.updateEntity(rating)

        // 创建一个表示之前状态的对象
        val prevEntity = RatingEntity {
            this.id = rating.id
            this.type = rating.type
            this.key = rating.key
            this.userId = rating.userId
            this.rating = prevRating
            this.rateTime = rating.rateTime
        }

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

    fun rateComment(commentId: String, raterId: String, ratingType: RatingType): Pair<RatingEntity, RatingEntity> =
        rate(Rating.KeyType.COMMENT, commentId, raterId, ratingType)

    fun rateCopilot(copilotId: Long, raterId: String, ratingType: RatingType): Pair<RatingEntity, RatingEntity> =
        rate(Rating.KeyType.COPILOT, copilotId.toString(), raterId, ratingType)

    fun findPersonalRatingOfCopilot(raterId: String, copilotId: Long): RatingEntity? =
        ratingKtormRepository.findByTypeAndKeyAndUserId(Rating.KeyType.COPILOT, copilotId.toString(), raterId)
}
