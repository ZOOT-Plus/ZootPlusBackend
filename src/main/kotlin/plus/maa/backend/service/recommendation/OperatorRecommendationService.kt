package plus.maa.backend.service.recommendation

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
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
        .build<ResultKey, RecommendationResult?>()
    private val catalogs = Caffeine.newBuilder().maximumSize(1).build<LocalDateTime, List<EvidenceStage>?>()
    private val calculations = Semaphore(2, true)

    fun recommend(query: RecommendationQuery): RecommendationResult {
        if (!query.isWindowSupported || !query.isCoverageSupported || !query.isCountSupported) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择支持的推荐预设")
        }
        repeat(3) {
            val version = versions.get(0)
            val time = version.time
            if (time == null) {
                version.rebuild!!.join()
            } else {
                val stages = catalogs.get(time) { snapshots.loadStages(time) }
                if (stages == null) {
                    versions.invalidateAll()
                    return@repeat
                }
                val normalized = normalize(query, stages)
                val result = results.get(ResultKey(time, normalized)) { calculate(time, normalized) }
                if (result != null) return result
            }
            // A daily replacement may occur between the lightweight version read and evidence load.
            versions.invalidateAll()
        }
        error("推荐数据快照暂不可用")
    }

    private fun normalize(query: RecommendationQuery, stages: List<EvidenceStage>): RecommendationQuery {
        val stageId = query.stageId?.trim()?.ifEmpty { null }
        val category = query.category?.trim()?.ifEmpty { null }
        val activity = query.activity?.trim()?.ifEmpty { null }
        val candidates = stages.filter {
            (category == null || it.category == category) && (activity == null || it.activity == activity)
        }
        val stage = stageId?.let { id ->
            candidates.firstOrNull { it.id.equals(id, true) }
                ?: candidates.firstOrNull { it.levelId.equals(id, true) }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "关卡不存在或与所选内容不符")
        }
        if ((category != null || activity != null) && candidates.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "分类或活动不在当前推荐目录中")
        }
        return query.copy(
            stageId = stage?.let { selected ->
                if (selected.id.equals(stageId, true) || candidates.count { it.id == selected.id } == 1) {
                    selected.id
                } else {
                    selected.levelId
                }
            },
            category = category,
            activity = activity,
            days = if (query.scope == RecommendationScope.PERMANENT || query.days == 0 && query.scope != RecommendationScope.HISTORY) {
                180
            } else {
                query.days
            },
            includeClosed = query.scope != RecommendationScope.PERMANENT && query.includeClosed,
        )
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

    private fun calculate(time: LocalDateTime, query: RecommendationQuery): RecommendationResult? {
        calculations.acquire()
        try {
            val loadStartedAt = System.nanoTime()
            val evidence = snapshots.load(time)
            val loadedMs = (System.nanoTime() - loadStartedAt) / 1_000_000
            if (evidence == null) return null
            val calculateStartedAt = System.nanoTime()
            val result = RecommendationEngine().calculate(evidence, query, time)
            log.debug {
                "推荐查询未命中缓存：加载证据 ${loadedMs}ms，计算 ${(System.nanoTime() - calculateStartedAt) / 1_000_000}ms，条件 $query"
            }
            return result
        } finally {
            calculations.release()
        }
    }

    private fun buildEvidence(): RecommendationEvidence {
        val startedAt = System.nanoTime()
        val operators = recommendationOperators(runBlocking { levelService.gameData() })
        log.info { "推荐证据构建开始：干员目录 ${operators.size} 位" }
        val engine = RecommendationEngine(operators)
        var pageReadNs = 0L
        var feedbackNs = 0L
        val rows = sequence {
            var afterId = 0L
            var pages = 0
            var seen = 0
            while (true) {
                val pageStartedAt = System.nanoTime()
                val page = copilotRepository.findPublicRecommendationPage(afterId, 1000)
                pageReadNs += System.nanoTime() - pageStartedAt
                if (page.isEmpty()) break
                val feedbackStartedAt = System.nanoTime()
                val feedback = ratingRepository.latestPositiveCopilotTimes(page.map { it.copilotId.toString() })
                feedbackNs += System.nanoTime() - feedbackStartedAt
                for (row in page) yield(RecommendationInput(row, feedback[row.copilotId.toString()]))
                afterId = page.last().copilotId
                pages++
                seen += page.size
                log.debug { "推荐证据读取第 $pages 页，累计 $seen 条，已耗时 ${(System.nanoTime() - startedAt) / 1_000_000}ms" }
            }
        }
        val evidence = engine.prepare(rows, levelRepository.findAllOrdered())
        log.info {
            "推荐证据构建完成：总耗时 ${(System.nanoTime() - startedAt) / 1_000_000}ms；" +
                "数据库读取 ${pageReadNs / 1_000_000}ms，正面评价查询 ${feedbackNs / 1_000_000}ms"
        }
        return evidence
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
