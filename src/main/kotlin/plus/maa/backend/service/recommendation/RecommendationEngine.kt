package plus.maa.backend.service.recommendation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.service.level.ArkLevelType
import plus.maa.backend.service.model.CopilotType
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Pure calculations: the clock, source rows and game catalog are explicit inputs. */
internal class RecommendationEngine(catalog: List<RecommendationOperator> = emptyList()) {
    private val operatorsByName = catalog.groupBy { it.name }
    private val json = Json { ignoreUnknownKeys = true }

    internal data class Member(
        val operator: RecommendationOperator,
        val target: TrainingTarget,
        val share: Double,
        val alternative: Boolean = false,
    )
    internal data class Document(
        val id: Long,
        val title: String,
        val firstPublishedAt: LocalDateTime,
        val latestPositiveAt: LocalDateTime?,
        val views: Long,
        val likes: Long,
        val dislikes: Long,
        val stage: ArkLevelEntity,
        val difficulty: Int,
        val members: List<Member>,
        val skeleton: String,
        val variant: String,
    ) {
        val stageDifficultyKey = "${stage.stageId}|$difficulty"
        val permanent = isPermanent(stage)
    }
    internal data class Family(val documents: List<Document>) {
        val firstPublishedAt: LocalDateTime = documents.minOf { it.firstPublishedAt }
        val variants: List<Document> = documents.groupBy { it.variant }.values.map { copies ->
            // Copies are correlated evidence. Keep one evaluation, never add their votes or views.
            copies.minWith(
                compareByDescending<Document> {
                    it.likes + it.dislikes
                }.thenBy { it.id },
            )
        }
    }
    private data class Sample(
        val variant: EvidenceVariant,
        val stage: EvidenceStage,
        val member: Member,
        val family: Int,
        val weight: Double,
        val recent: Boolean,
        val quality: Double,
    )
    private data class FamilyEvidence(var quality: Sample, var source: Sample)

    fun calculate(
        inputs: List<RecommendationInput>,
        levels: List<ArkLevelEntity>,
        query: RecommendationQuery,
        now: LocalDateTime,
    ): RecommendationResult = calculate(prepare(inputs, levels), query, now)

    fun prepare(inputs: List<RecommendationInput>, levels: List<ArkLevelEntity>): RecommendationEvidence =
        prepare(inputs.asSequence(), levels)

    fun prepare(inputs: Sequence<RecommendationInput>, levels: List<ArkLevelEntity>): RecommendationEvidence {
        val catalog = levels.filter { !it.stageId.isNullOrBlank() }
        val levelIndex = buildMap {
            catalog.forEach { level ->
                listOfNotNull(level.stageId, level.levelId).filter {
                    it.isNotBlank()
                }.forEach { putIfAbsent(it.trim().lowercase(), level) }
            }
            catalog.flatMap { level ->
                listOfNotNull(level.catThree, level.name).filter { it.isNotBlank() }.map { it.trim().lowercase() to level }
            }.groupBy({ it.first }, { it.second }).forEach { (alias, matches) ->
                // Bare names and codes prefer the normal variant, but must identify a single stage.
                matches.sortedBy { it.stageId!!.length }.distinctBy {
                    it.stageId!!.removeSuffix("#f#").replace("bossrush_tm", "bossrush_")
                }.singleOrNull()?.let { putIfAbsent(alias, it) }
            }
        }
        var invalid = 0
        val digest = MessageDigest.getInstance("SHA-256")
        val documents = inputs.filter { it.entity.type == CopilotType.PRTS }.mapNotNull { input ->
            parse(input, levelIndex, digest).also { if (it == null) invalid++ }
        }.toList()
        // Anchor clusters avoid transitive chains merging substantially different strategies.
        val families = documents.groupBy { "${it.stageDifficultyKey}|${it.skeleton}" }.values.flatMap { bucket ->
            val clusters = mutableListOf<Pair<Set<String>, MutableList<Document>>>()
            bucket.sortedBy { it.firstPublishedAt }.forEach { document ->
                val identities = document.members.map { it.operator.id }.toSet()
                val match = clusters.firstOrNull { (anchor, _) ->
                    2.0 * identities.count { it in anchor } / (identities.size + anchor.size).coerceAtLeast(1) >= 0.85
                }
                if (match == null) clusters.add(identities to mutableListOf(document)) else match.second.add(document)
            }
            clusters.map { Family(it.second.toList()) }
        }
        val operators = documents.flatMap { it.members }.map { it.operator }.distinctBy { it.id }
        val operatorIndices = operators.mapIndexed { index, operator -> operator.id to index }.toMap()
        fun stage(document: Document): EvidenceStage = document.stage.let {
            EvidenceStage(
                it.stageId.orEmpty(),
                it.levelId,
                listOfNotNull(it.catThree, it.name).joinToString(" "),
                it.catOne,
                it.catTwo,
                it.isOpen,
                document.permanent,
            )
        }
        val stages = documents.map(::stage).distinct()
        val stageIndices = stages.mapIndexed { index, entry -> entry to index }.toMap()
        return RecommendationEvidence(
            operators,
            stages,
            families.map { family ->
                val first = family.documents.first()
                EvidenceFamily(
                    stageIndices.getValue(stage(first)),
                    first.difficulty,
                    family.firstPublishedAt,
                    family.documents.size,
                    family.variants.map { document ->
                        EvidenceVariant(
                            document.id,
                            document.title,
                            document.firstPublishedAt,
                            document.latestPositiveAt,
                            document.views,
                            document.likes,
                            document.dislikes,
                            document.members.map { member ->
                                EvidenceMember(
                                    operatorIndices.getValue(member.operator.id),
                                    member.target,
                                    member.share,
                                    member.alternative,
                                )
                            },
                        )
                    },
                )
            },
            invalid,
        )
    }

    fun calculate(prepared: RecommendationEvidence, query: RecommendationQuery, now: LocalDateTime): RecommendationResult {
        val window = if (query.days == 0) 180 else query.days
        val cutoff = now.minusDays(window.toLong())
        val available = prepared.families.filter { family ->
            val stage = prepared.stages[family.stage]
            val permanent = stage.permanent
            val recent = family.firstPublishedAt >= cutoff || family.variants.any {
                (it.latestPositiveAt ?: LocalDateTime.MIN) >= cutoff && wilson(it.likes, it.dislikes).first >= 0.5
            }
            (query.includeClosed || stage.isOpen != false || permanent) &&
                when (query.scope) {
                    RecommendationScope.RECENT -> recent || permanent
                    RecommendationScope.PERMANENT -> permanent
                    RecommendationScope.HISTORY -> query.days == 0 || recent
                }
        }
        val categoryFamilies = available.filter { query.category.isNullOrBlank() || query.category == prepared.stages[it.stage].category }
        val activityFamilies = categoryFamilies.filter {
            query.activity.isNullOrBlank() || query.activity == prepared.stages[it.stage].activity
        }
        val activityCounts = categoryFamilies.groupingBy { prepared.stages[it.stage].activity }.fold(0) { count, family ->
            count + family.operationCount
        }
        val stageCounts = activityFamilies.groupingBy { prepared.stages[it.stage].id }
            .fold(0) { count, family -> count + family.operationCount }
        val selected = activityFamilies.filter {
            val stage = prepared.stages[it.stage]
            query.stageId.isNullOrBlank() || query.stageId == stage.id || query.stageId == stage.levelId
        }
        fun stageKey(family: EvidenceFamily) = "${prepared.stages[family.stage].id}|${family.difficulty}"
        val stageFamilies = selected.groupingBy(::stageKey).eachCount()
        val exposure = selected.flatMap { family -> family.variants.map { family to it } }
            .groupBy { (family, variant) -> "${stageKey(family)}|${ageBucket(variant.firstPublishedAt, now)}" }
            .mapValues { (_, rows) -> rows.map { ln(1.0 + it.second.views.coerceAtLeast(0)) }.sorted().let { it[it.size / 2] } }
        val stageDocuments = selected.distinctBy(::stageKey)
        val categoryStages = stageDocuments.groupBy {
            prepared.stages[it.stage].let { stage -> stage.permanent to stage.category.orEmpty() }
        }
            .mapValues { (_, rows) -> rows.groupingBy { prepared.stages[it.stage].activity.orEmpty() }.eachCount() }
        val permanentCategories = categoryStages.keys.count { it.first }
        val otherCategories = categoryStages.keys.count { !it.first }
        val availableBudget = (if (permanentCategories > 0) 0.3 else 0.0) + (if (otherCategories > 0) 0.7 else 0.0)
        val stageWeights = stageDocuments.associate { family ->
            val stage = prepared.stages[family.stage]
            val activities = categoryStages.getValue(stage.permanent to stage.category.orEmpty())
            val categoryWeight = (if (stage.permanent) 0.3 / permanentCategories else 0.7 / otherCategories) / availableBudget
            stageKey(family) to categoryWeight / activities.size / activities.getValue(stage.activity.orEmpty())
        }
        val samples = mutableMapOf<String, MutableList<Sample>>()
        selected.forEachIndexed { familyId, family ->
            val variants = family.variants
            variants.forEach { variant ->
                val (lower, upper) = wilson(variant.likes, variant.dislikes)
                if (upper < 0.6) return@forEach
                val provisional = variant.likes + variant.dislikes == 0L
                if (provisional && !query.includeUncertain) return@forEach
                val quality = if (provisional) 0.05 else lower
                val ageHours = ChronoUnit.HOURS.between(family.firstPublishedAt, now).coerceAtLeast(0).toDouble()
                val mature = 0.25 + 0.75 * (ageHours / 72.0).coerceIn(0.0, 1.0)
                val publishAge = ageHours / 24.0
                val feedback = variant.latestPositiveAt
                val feedbackAge = feedback?.let { ChronoUnit.DAYS.between(it, now).coerceAtLeast(0).toDouble() }
                val verifiedAge = if (lower >= 0.5 && feedbackAge != null) minOf(publishAge, feedbackAge + 90.0) else publishAge
                val recency = 2.0.pow(-verifiedAge / 180.0)
                val baseline = exposure["${stageKey(family)}|${ageBucket(variant.firstPublishedAt, now)}"] ?: 0.0
                val viewAdjustment = if (baseline <= 0.0) 1.0 else (ln(1.0 + variant.views.coerceAtLeast(0)) / baseline).coerceIn(0.9, 1.1)
                val recent = family.firstPublishedAt >= cutoff || (lower >= 0.5 && feedback != null && feedback >= cutoff)
                val weight =
                    quality * mature * recency * viewAdjustment / variants.size / stageFamilies.getValue(stageKey(family))
                val balance = stageWeights.getValue(stageKey(family))
                for (entry in variant.members) {
                    if (query.includeAlternatives || !entry.alternative) {
                        val member = Member(prepared.operators[entry.operator], entry.target, entry.share, entry.alternative)
                        samples.getOrPut(member.operator.id) { mutableListOf() }
                            .add(
                                Sample(
                                    variant,
                                    prepared.stages[family.stage],
                                    member,
                                    familyId,
                                    weight * member.share * balance,
                                    recent,
                                    lower,
                                ),
                            )
                    }
                }
            }
        }
        val recommendations = samples.values.mapNotNull { evidence ->
            val stageIds = mutableSetOf<String>()
            val recentStageIds = mutableSetOf<String>()
            val recentFamilies = mutableSetOf<Int>()
            val independent = mutableMapOf<Int, FamilyEvidence>()
            evidence.forEach { sample ->
                val stageId = sample.stage.id
                stageIds.add(stageId)
                if (sample.recent && sample.quality > 0.0) {
                    recentStageIds.add(stageId)
                    recentFamilies.add(sample.family)
                }
                val family = independent[sample.family]
                if (family == null) {
                    independent[sample.family] = FamilyEvidence(sample, sample)
                } else {
                    if (sample.quality > family.quality.quality) family.quality = sample
                    if (sample.weight > family.source.weight) family.source = sample
                }
            }
            val (qualityFloor, _) = wilson(
                independent.values.sumOf { it.quality.variant.likes },
                independent.values.sumOf { it.quality.variant.dislikes },
            )
            val strong = qualityFloor >= 0.5
            val focused = !query.stageId.isNullOrBlank() || !query.activity.isNullOrBlank()
            val current = strong && recentStageIds.size >= (if (focused) 1 else 2) && recentFamilies.size >= (if (focused) 1 else 3)
            if (query.scope == RecommendationScope.RECENT && !query.includeUncertain && !current) return@mapNotNull null
            val status = when {
                !strong -> "INSUFFICIENT"
                current -> "CURRENT"
                recentFamilies.isNotEmpty() -> "LIMITED"
                else -> "HISTORICAL"
            }
            val weight = evidence.sumOf { it.weight }
            if (weight <= 0.0) return@mapNotNull null
            val sources = mutableListOf<Sample>()
            independent.values.forEach { family ->
                val position = sources.indexOfFirst { it.weight < family.source.weight }
                if (position >= 0) {
                    sources.add(position, family.source)
                } else if (sources.size < 5) {
                    sources.add(family.source)
                }
                if (sources.size > 5) sources.removeLast()
            }
            OperatorRecommendation(
                operator = evidence.first().member.operator,
                score = (100.0 * weight).coerceAtMost(100.0),
                stageCount = stageIds.size,
                familyCount = independent.size,
                recentStageCount = recentStageIds.size,
                recentFamilyCount = recentFamilies.size,
                status = status,
                branches = branches(evidence, query.coverage),
                sources = sources.map { sample ->
                    val variant = sample.variant
                    RecommendationSource(
                        id = variant.id,
                        title = variant.title,
                        stageId = sample.stage.id,
                        firstPublishedAt = variant.firstPublishedAt.toString(),
                        likes = variant.likes,
                        dislikes = variant.dislikes,
                        views = variant.views,
                        quality = sample.quality,
                        alternative = sample.member.alternative,
                    )
                },
            )
        }.sortedWith(compareByDescending<OperatorRecommendation> { it.score }.thenBy { it.operator.id })
        return RecommendationResult(
            generatedAt = now.toString(),
            operationCount = selected.sumOf { it.operationCount },
            familyCount = selected.size,
            invalidCount = prepared.invalidCount,
            categories = prepared.stages.mapNotNull { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
            activities = prepared.stages.mapNotNull { it.activity }
                .filter { it.isNotBlank() && !it.startsWith("level_", ignoreCase = true) }.distinct().sorted()
                .filter { activityCounts.getOrDefault(it, 0) >= query.minOperationCount },
            stages = prepared.stages.filter { stageCounts.getOrDefault(it.id, 0) >= query.minOperationCount }
                .distinctBy { it.id }.map { RecommendationStage(it.id, it.name, it.category, it.activity) }.sortedBy { it.name },
            recommendations = recommendations,
        )
    }

    private fun parse(input: RecommendationInput, levels: Map<String, ArkLevelEntity>, digest: MessageDigest): Document? {
        val root = runCatching { json.parseToJsonElement(input.entity.content) as? JsonObject }.getOrNull() ?: return null
        val stageName = root.string("stage_name") ?: input.entity.stageName
        val stage = levels[input.entity.stageName.trim().lowercase()] ?: levels[stageName.trim().lowercase()] ?: return null
        val slots = mutableListOf<List<Member>>()
        val aliases = mutableMapOf<String, String>()
        val fixed = root.array("opers").mapNotNull { parseMember(it as? JsonObject, root.number("version")) }
        fixed.forEach { member -> aliases[member.operator.name] = member.operator.id }
        slots.add(fixed)
        root.array("groups").forEach { element ->
            val group = element as? JsonObject ?: return@forEach
            val alternatives = group.array("opers").map {
                parseMember(it as? JsonObject, root.number("version"))
            }
            val candidates = alternatives.filterNotNull().distinctBy { it.operator.id to it.target }
            if (candidates.isNotEmpty()) {
                val count = candidates.size + alternatives.count { it == null }
                slots.add(candidates.map { it.copy(share = 1.0 / count, alternative = true) })
                group.string("name")?.let { aliases[it] = candidates.map { it.operator.id }.distinct().sorted().joinToString(",") }
            }
        }
        val members = slots.flatten()
        if (members.isEmpty() || stageName.isBlank()) return null
        val actors = mutableMapOf<String, Int>()
        val ignored = setOf("doc", "doc_color", "_id")
        val actions = root.array("actions").mapNotNull { it as? JsonObject }.map { action ->
            val type = action.string("type") ?: "Deploy"
            val direction = action.string("direction") ?: "None"
            JsonObject(
                action + mapOf(
                    "type" to JsonPrimitive(actionAliases[type] ?: type),
                    "direction" to JsonPrimitive(directionAliases[direction] ?: direction),
                ),
            )
        }.filter { it.string("type") != "Output" }
        val skeleton = if (actions.isEmpty()) {
            "lineup:${members.map { it.operator.id }.distinct().sorted()}"
        } else {
            actions.joinToString(";") { action ->
                val name = action.string("name")
                val actor = name?.let { actors.getOrPut(aliases[it] ?: it) { actors.size } }
                listOf(
                    action.string("type") ?: "Deploy",
                    actor,
                    action["location"],
                    action["direction"],
                    action["rect"],
                    action["begin"],
                    action["end"],
                    action["distance"],
                ).joinToString(":")
            }
        }
        val unique = slots.flatMapIndexed { index, slot ->
            slot.map { index to it }
        }.groupBy { it.second.operator.id }.values.flatMap { entries ->
            val required = entries.firstOrNull { !it.second.alternative }
            if (required != null) {
                listOf(required.second)
            } else {
                // Repeated slots count once, while different training choices within that slot retain their shares.
                entries.groupBy { it.first }.values.maxBy { slot -> slot.sumOf { it.second.share } }.map { it.second }
            }
        }
        val controls = (root.array("opers") + root.array("groups").flatMap { (it as? JsonObject)?.array("opers").orEmpty() })
            .mapNotNull { it as? JsonObject }.map { oper ->
                listOf(
                    oper.string("name")?.trim(),
                    oper.string("role").orEmpty(),
                    oper.number("skill_usage") ?: 0,
                    oper.number("skill_times") ?: 1,
                )
            }.sortedBy { it.toString() }
        val executable = actions.map { action ->
            val fields = action.filterKeys { it !in ignored }.toMutableMap()
            action.string("name")?.let { fields["name"] = JsonPrimitive(aliases[it] ?: it) }
            canonical(JsonObject(fields))
        }
        val variant = controls.toString() + unique.sortedBy { it.operator.id }
            .joinToString { "${it.operator.id}|${it.target}|${it.share}|${it.alternative}" } + executable.joinToString()
        fun fingerprint(value: String) = HexFormat.of().formatHex(digest.digest(value.toByteArray(Charsets.UTF_8)))
        val row = input.entity
        return Document(
            row.copilotId, row.title, row.firstUploadTime, input.latestPositiveAt, row.views, row.likeCount, row.dislikeCount,
            stage, root.number("difficulty") ?: 0, unique, fingerprint(skeleton), fingerprint(variant),
        )
    }

    private fun parseMember(value: JsonObject?, version: Int?): Member? {
        value ?: return null
        val name = value.string("name")?.trim() ?: return null
        val possible = operatorsByName[name].orEmpty().filter { value.string("role").isNullOrBlank() || it.role == value.string("role") }
        val operator = possible.singleOrNull() ?: return null
        val req = value["requirements"] as? JsonObject ?: JsonObject(emptyMap())
        val elite = req.number("elite")
        val level = req.number("level")?.takeIf { it > 0 }
        val skill = if (operator.rarity <= 2) 0 else value.number("skill") ?: 1
        val skillLevel = if (operator.rarity <= 2) null else req.number("skill_level")?.takeIf { it > 0 }
        if ((req.number("level") ?: 0) < 0 || (req.number("skill_level") ?: 0) < 0) return null
        val rawModule = req.number("module")
        val module = when {
            rawModule == null || rawModule == -1 -> null
            rawModule < -1 -> return null
            rawModule == 0 -> 0
            version == 2 -> operator.modules.getOrNull(rawModule) ?: return null
            else -> rawModule
        }
        val caps = levelCaps(operator.rarity)
        if (elite != null && elite !in caps.indices) return null
        if (level != null && level > (elite?.let { caps[it] } ?: caps.max())) return null
        val skillCount = when {
            operator.rarity <= 2 -> 0
            operator.rarity == 3 -> 1
            operator.rarity == 6 || (operator.name == "阿米娅" && operator.role == "Caster") -> 3
            else -> 2
        }
        if (skill !in (if (skillCount == 0) 0..0 else 1..skillCount)) return null
        if (skillLevel != null && skillLevel !in 1..(if (operator.rarity == 3) 7 else 10)) return null
        if (module != null && module > 0 && module !in operator.modules) return null
        // Source fields are lower bounds; skill and module unlocks constrain the recommended configuration.
        return Member(operator, TrainingTarget(elite, level, skill, skillLevel, module), 1.0)
    }

    private fun branches(samples: List<Sample>, requested: Double): List<TrainingBranch> {
        val total = samples.sumOf { it.weight }
        return samples.groupBy { it.member.target.skill to it.member.target.module }.values.flatMap { group ->
            val branchWeight = group.sumOf { it.weight }
            val requirements = group.groupingBy { it.member.target }.fold(0.0) { weight, sample -> weight + sample.weight }
            val targets = requirements.keys.toList()
            val base = targets.first()
            val operator = group.first().member.operator
            val elites = (targets.map { it.elite } + levelCaps(operator.rarity).indices).distinct()
            val levels = (
                targets.map { it.level } + 1 +
                    listOfNotNull(base.module?.takeIf { it > 0 }?.let { moduleUnlockLevel(operator.rarity) })
                ).distinct()
            val skills = targets.map { it.skillLevel }.distinct().sortedWith(nullsFirst())
            val maxLevel = levelCaps(operator.rarity).max()
            val coverageTable = if (targets.size * elites.size * levels.size * skills.size > 4 * (maxLevel + 1) * 11) {
                CoverageTable(requirements, maxLevel)
            } else {
                null
            }
            // Level resets on promotion; enumerate legal thresholds rather than reusing incomplete source pairs.
            val qualifying = elites.flatMap { elite ->
                levels.mapNotNull { level ->
                    // Higher skill thresholds at the same phase and level are always dominated by the first match.
                    skills.firstNotNullOfOrNull { skill ->
                        val target = base.copy(elite = elite, level = level, skillLevel = skill)
                        if (!isFeasible(target, operator)) return@firstNotNullOfOrNull null
                        val covered = coverageTable?.covered(target)
                            ?: requirements.entries.sumOf { (requirement, weight) -> if (covers(target, requirement)) weight else 0.0 }
                        val coverage = (covered / branchWeight).coerceIn(0.0, 1.0)
                        (target to coverage).takeIf { coverage + 1e-9 >= requested }
                    }
                }
            }
            val minimal = qualifying.filter { (candidate, _) ->
                qualifying.none { (other, _) ->
                    other != candidate &&
                        covers(candidate, other) &&
                        !covers(other, candidate)
                }
            }
            val levelDataRatio =
                group.filter { it.member.target.elite != null && it.member.target.level != null }.sumOf { it.weight } / branchWeight
            val skillDataRatio = group.filter { it.member.target.skillLevel != null }.sumOf { it.weight } / branchWeight
            val moduleDataRatio = group.filter { it.member.target.module != null }.sumOf { it.weight } / branchWeight
            minimal.map { (target, coverage) ->
                TrainingBranch(
                    target,
                    branchWeight / total,
                    coverage,
                    levelDataRatio,
                    skillDataRatio,
                    moduleDataRatio,
                )
            }
        }.sortedWith(compareByDescending<TrainingBranch> { it.usageShare }.thenBy { it.target.skill }.thenBy { it.target.module ?: -1 })
    }

    /** Prefix sums per promotion phase preserve unknown fields and the level reset on promotion. */
    private class CoverageTable(requirements: Map<TrainingTarget, Double>, private val maxLevel: Int) {
        private val sums = Array(4) { Array(maxLevel + 1) { DoubleArray(11) } }

        init {
            requirements.forEach { (target, weight) ->
                sums[(target.elite ?: -1) + 1][target.level ?: 0][target.skillLevel ?: 0] += weight
            }
            sums.forEach { phase ->
                for (level in 0..maxLevel) {
                    for (skill in 0..10) {
                        phase[level][skill] += (if (level > 0) phase[level - 1][skill] else 0.0) +
                            (if (skill > 0) phase[level][skill - 1] else 0.0) -
                            (if (level > 0 && skill > 0) phase[level - 1][skill - 1] else 0.0)
                    }
                }
            }
        }

        fun covered(target: TrainingTarget): Double {
            val level = target.level ?: 0
            val skill = target.skillLevel ?: 0
            var result = sums[0][level][skill]
            target.elite?.let { elite ->
                for (phase in 0..elite) result += sums[phase + 1][if (phase < elite) maxLevel else level][skill]
            }
            return result
        }
    }

    private fun isFeasible(target: TrainingTarget, operator: RecommendationOperator): Boolean {
        val module = (target.module ?: 0) > 0
        val unlockElite = maxOf(
            (target.skill - 1).coerceAtLeast(0),
            if ((target.skillLevel ?: 0) > 7) {
                2
            } else if ((target.skillLevel ?: 0) > 4) {
                1
            } else {
                0
            },
            if (module) 2 else 0,
        )
        val elite = target.elite ?: return unlockElite == 0 && target.level == null
        val caps = levelCaps(operator.rarity)
        if (elite !in caps.indices || elite < unlockElite) return false
        if (target.level != null && target.level !in 1..caps[elite]) return false
        return !module || (target.level ?: 0) >= moduleUnlockLevel(operator.rarity)
    }

    private fun covers(candidate: TrainingTarget, sample: TrainingTarget): Boolean {
        if (candidate.skill != sample.skill || candidate.module != sample.module) return false
        if (sample.elite != null && (candidate.elite == null || candidate.elite < sample.elite)) return false
        if (sample.level != null && (sample.elite == null || candidate.elite == sample.elite) &&
            (candidate.level == null || candidate.level < sample.level)
        ) {
            return false
        }
        if (sample.skillLevel != null && (candidate.skillLevel == null || candidate.skillLevel < sample.skillLevel)) return false
        return true
    }

    companion object {
        private val actionAliases = mapOf(
            "部署" to "Deploy", "技能" to "Skill", "撤退" to "Retreat", "二倍速" to "SpeedUp",
            "子弹时间" to "BulletTime", "技能用法" to "SkillUsage", "打印" to "Output", "摆完挂机" to "SkillDaemon",
            "移动相机" to "MoveCamera", "点击" to "Click", "滑动" to "Swipe", "设置单位坐标" to "SetUnitLocation",
        )
        private val directionAliases = mapOf("左" to "Left", "右" to "Right", "上" to "Up", "下" to "Down", "无" to "None")
        private fun levelCaps(rarity: Int) = when (rarity) {
            1, 2 -> listOf(30)
            3 -> listOf(40, 55)
            4 -> listOf(45, 60, 70)
            5 -> listOf(50, 70, 80)
            else -> listOf(50, 80, 90)
        }
        internal fun wilson(likes: Long, dislikes: Long): Pair<Double, Double> {
            val n = likes.coerceAtLeast(0).toDouble() + dislikes.coerceAtLeast(0).toDouble()
            if (n == 0.0) return 0.0 to 1.0
            val p = likes.coerceAtLeast(0).toDouble() / n
            val z2 = 1.96 * 1.96
            val center = p + z2 / (2 * n)
            val margin = 1.96 * sqrt((p * (1 - p) + z2 / (4 * n)) / n)
            val divisor = 1 + z2 / n
            return ((center - margin) / divisor).coerceIn(0.0, 1.0) to ((center + margin) / divisor).coerceIn(0.0, 1.0)
        }
        private fun moduleUnlockLevel(rarity: Int) = when (rarity) {
            4 -> 40
            5 -> 50
            else -> 60
        }
        private fun isPermanent(stage: ArkLevelEntity) =
            ArkLevelType.fromLevelId(stage.levelId) in setOf(ArkLevelType.MAINLINE, ArkLevelType.WEEKLY, ArkLevelType.CAMPAIGN)
        private fun ageBucket(time: LocalDateTime, now: LocalDateTime) = when (ChronoUnit.DAYS.between(time, now)) {
            in Long.MIN_VALUE..7 -> 0
            in 8..30 -> 1
            in 31..180 -> 2
            else -> 3
        }
        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
        private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
        private fun canonical(value: JsonElement): String = buildString { appendCanonical(value) }
        private fun StringBuilder.appendCanonical(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    append('{')
                    value.keys.sorted().forEachIndexed { index, key ->
                        if (index > 0) append(',')
                        append(JsonPrimitive(key)).append(':')
                        appendCanonical(value.getValue(key))
                    }
                    append('}')
                }
                is JsonArray -> {
                    append('[')
                    value.forEachIndexed { index, element ->
                        if (index > 0) append(',')
                        appendCanonical(element)
                    }
                    append(']')
                }
                else -> append(value)
            }
        }
    }
}
