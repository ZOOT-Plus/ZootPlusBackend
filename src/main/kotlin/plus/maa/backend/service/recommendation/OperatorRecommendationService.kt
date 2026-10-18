package plus.maa.backend.service.recommendation

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import plus.maa.backend.repository.ktorm.CopilotRepository
import plus.maa.backend.repository.ktorm.RatingRepository
import plus.maa.backend.repository.ktorm.RecommendationSnapshotRepository
import plus.maa.backend.service.level.ArkGameDataHolder
import plus.maa.backend.service.level.ArkLevelService
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

@Service
class OperatorRecommendationService(
    private val copilotRepository: CopilotRepository,
    private val ratingRepository: RatingRepository,
    private val levelRepository: ArkLevelRepository,
    private val levelService: ArkLevelService,
    private val snapshots: RecommendationSnapshotRepository,
) {
    private data class Version(val time: LocalDateTime?, val rebuild: CompletableFuture<Unit>?)
    private data class ResultKey(val time: LocalDateTime, val query: RecommendationQuery)

    private val log = KotlinLogging.logger { }
    private val building = AtomicReference<CompletableFuture<Unit>?>()
    private val versions = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(1)).build<Int, Version> {
        val time = snapshots.generatedAt()
        Version(time, if (time == null || time.toLocalDate() < now().toLocalDate()) rebuild() else null)
    }
    private val results = Caffeine.newBuilder().maximumSize(16).expireAfterAccess(Duration.ofMinutes(30))
        .build<ResultKey, RecommendationResult>()
    private val calculations = Semaphore(2, true)

    fun recommend(query: RecommendationQuery): RecommendationResult {
        val normalized = query.copy(
            stageId = query.stageId?.trim()?.ifEmpty { null },
            category = query.category?.trim()?.ifEmpty { null },
            activity = query.activity?.trim()?.ifEmpty { null },
            days = if (query.days == 0 && query.scope != RecommendationScope.HISTORY) 180 else query.days,
        )
        repeat(3) {
            val version = versions.get(0)
            val time = version.time
            if (time == null) {
                version.rebuild!!.join()
            } else {
                val result = results.asMap().computeIfAbsent(ResultKey(time, normalized)) {
                    calculations.acquire()
                    try {
                        snapshots.load(time)?.let { RecommendationEngine().calculate(it, normalized, time) }
                    } finally {
                        calculations.release()
                    }
                }
                if (result != null) return result
            }
            // A daily replacement may occur between the lightweight version read and evidence load.
            versions.invalidateAll()
        }
        error("推荐数据快照暂不可用")
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    fun refresh() {
        rebuild()
    }

    internal fun rebuild(): CompletableFuture<Unit> {
        building.get()?.let { return it }
        val pending = CompletableFuture<Unit>()
        if (!building.compareAndSet(null, pending)) return rebuild()
        Thread.ofVirtual().name("recommendation-evidence-build").start {
            val outcome = runCatching {
                if (snapshots.rebuildIfNeeded(now()) { buildEvidence() }) results.invalidateAll()
                versions.invalidateAll()
            }
            building.compareAndSet(pending, null)
            outcome.fold(
                { pending.complete(Unit) },
                { error ->
                    log.error(error) { "每日推荐证据构建失败，保留上一份数据" }
                    pending.completeExceptionally(error)
                },
            )
        }
        return pending
    }

    private fun now(): LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))

    private fun buildEvidence(): RecommendationEvidence {
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
        return engine.prepare(rows, levelRepository.findAllOrdered())
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
