package plus.maa.backend.service.recommendation

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.serialization.json.Json
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.service.level.ArkLevelsSyncedEvent
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class OperatorRecommendationService(
    private val copilotRepository: CopilotRepository,
    private val ratingRepository: RatingRepository,
    private val levelRepository: ArkLevelRepository,
) {
    private val engine = RecommendationEngine(
        Json.decodeFromString<List<RecommendationOperator>>(
            requireNotNull(javaClass.getResourceAsStream("/recommendation-operators.json")).bufferedReader().use { it.readText() },
        ),
    )
    private data class Snapshot(val inputs: List<RecommendationInput>, val levels: List<ArkLevelEntity>, val time: LocalDateTime)
    private val snapshots = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(15)).build<Int, Snapshot>()
    private val results = Caffeine.newBuilder().maximumSize(
        64,
    ).expireAfterWrite(Duration.ofMinutes(15)).build<RecommendationQuery, RecommendationResult>()

    // ponytail: one lock protects cold builds and invalidation; split by generation if concurrent CPU load warrants it.
    @Synchronized
    fun recommend(query: RecommendationQuery): RecommendationResult {
        val snapshot = snapshots.get(0) { load() }
        val cached = results.getIfPresent(query)
        if (cached?.generatedAt == snapshot.time.toString()) return cached
        return engine.calculate(snapshot.inputs, snapshot.levels, query, snapshot.time).also { results.put(query, it) }
    }

    @Synchronized
    fun invalidate() {
        snapshots.invalidateAll()
        results.invalidateAll()
    }

    @EventListener
    fun onLevelsChanged(event: ArkLevelsSyncedEvent) = invalidate()

    private fun load(): Snapshot {
        val rows = mutableListOf<RecommendationInput>()
        var afterId = 0L
        while (true) {
            val page = copilotRepository.findPublicRecommendationPage(afterId, 1000)
            if (page.isEmpty()) break
            val feedback = ratingRepository.latestPositiveCopilotTimes(page.map { it.copilotId.toString() })
            rows.addAll(page.map { RecommendationInput(it, feedback[it.copilotId.toString()]) })
            afterId = page.last().copilotId
        }
        return Snapshot(rows, levelRepository.findAllOrdered(), LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
    }
}
