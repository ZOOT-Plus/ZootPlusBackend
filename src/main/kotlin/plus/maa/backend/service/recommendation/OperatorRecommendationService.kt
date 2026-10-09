package plus.maa.backend.service.recommendation

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.service.level.ArkGameDataHolder
import plus.maa.backend.service.level.ArkLevelOpenStatusChangedEvent
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.level.ArkLevelsSyncedEvent
import plus.maa.backend.service.model.CopilotSetStatus
import plus.maa.backend.service.model.CopilotType
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class OperatorRecommendationService(
    private val copilotRepository: CopilotRepository,
    private val ratingRepository: RatingRepository,
    private val levelRepository: ArkLevelRepository,
    private val levelService: ArkLevelService,
) {
    private data class Snapshot(val engine: RecommendationEngine, val prepared: RecommendationEngine.Prepared, val time: LocalDateTime) {
        val results = Caffeine.newBuilder().maximumSize(64).build<RecommendationQuery, RecommendationResult>()
    }

    // Replacing the value preserves the entry's original expiry and isolates in-flight calculations.
    private class SnapshotEntry(var value: Snapshot)
    private val snapshots = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(15)).build<Int, SnapshotEntry>()

    fun recommend(query: RecommendationQuery): RecommendationResult {
        val snapshot = synchronized(this) { snapshots.get(0) { SnapshotEntry(load()) }.value }
        return snapshot.results.get(query) { snapshot.engine.calculate(snapshot.prepared, query, snapshot.time) }
    }

    @Synchronized
    fun refreshFeedback(copilotId: Long) {
        val entry = snapshots.getIfPresent(0) ?: return
        val snapshot = entry.value
        if (copilotId !in snapshot.prepared.documentsById) return
        val row = copilotRepository.findNotDeletedCopilotId(copilotId)
        if (row == null || row.status != CopilotSetStatus.PUBLIC || row.type != CopilotType.PRTS) {
            invalidate()
            return
        }
        val feedback = ratingRepository.latestPositiveCopilotTimes(listOf(copilotId.toString()))
        entry.value =
            snapshot.copy(prepared = snapshot.prepared.withFeedback(RecommendationInput(row.copy(), feedback[copilotId.toString()])))
    }

    @Synchronized
    fun invalidate() {
        snapshots.invalidateAll()
    }

    @EventListener
    fun onLevelsChanged(event: ArkLevelsSyncedEvent) = invalidate()

    @EventListener
    fun onOpenStatusChanged(event: ArkLevelOpenStatusChangedEvent) = invalidate()

    private fun load(): Snapshot {
        val engine = RecommendationEngine(recommendationOperators(runBlocking { levelService.gameData() }))
        val rows = mutableListOf<RecommendationInput>()
        var afterId = 0L
        while (true) {
            val page = copilotRepository.findPublicRecommendationPage(afterId, 1000)
            if (page.isEmpty()) break
            val feedback = ratingRepository.latestPositiveCopilotTimes(page.map { it.copilotId.toString() })
            rows.addAll(page.map { RecommendationInput(it, feedback[it.copilotId.toString()]) })
            afterId = page.last().copilotId
        }
        return Snapshot(engine, engine.prepare(rows, levelRepository.findAllOrdered()), LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
    }
}

internal fun recommendationOperators(data: ArkGameDataHolder): List<RecommendationOperator> {
    val professions = setOf("PIONEER", "WARRIOR", "TANK", "SNIPER", "CASTER", "MEDIC", "SUPPORT", "SPECIAL")
    val moduleTypes = mapOf("" to 0, "X" to 1, "Y" to 2, "A" to 3, "D" to 4)
    return data.characters.mapNotNull { character ->
        val id = character.id ?: return@mapNotNull null
        if (character.profession !in professions || character.rarity !in 0..5 ||
            character.subProfessionId == "notchar1" || id == "char_512_aprot"
        ) {
            return@mapNotNull null
        }
        val modules = data.findEquips(id).sortedBy { it.charEquipOrder }
            .map { if (it.typeName1 == "ORIGINAL") "" else it.typeName2 }.distinct().map { moduleTypes[it] }
        RecommendationOperator(
            id,
            character.name,
            character.profession.lowercase().replaceFirstChar { it.titlecase() },
            character.rarity + 1,
            modules,
        )
    }
}
