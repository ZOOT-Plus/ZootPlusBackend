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
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Pure calculations: the clock, source rows and game catalog are explicit inputs. */
internal class RecommendationEngine(catalog: List<RecommendationOperator>) {
    private val operatorsByName = catalog.groupBy { it.name }
    private val json = Json { ignoreUnknownKeys = true }

    internal data class Member(
        val operator: RecommendationOperator,
        val target: TrainingTarget,
        val share: Double,
        val alternative: Boolean = false,
    )
    internal data class Document(
        val input: RecommendationInput,
        val stage: ArkLevelEntity,
        val difficulty: Int,
        val members: List<Member>,
        val skeleton: String,
        val variant: String,
    )
    internal data class Family(val documents: List<Document>) {
        val firstPublishedAt: LocalDateTime = documents.minOf { it.input.entity.firstUploadTime }
        val variants: List<Document> = documents.groupBy { it.variant }.values.map { copies ->
            // Copies are correlated evidence. Keep one evaluation, never add their votes or views.
            copies.sortedWith(
                compareByDescending<Document> {
                    it.input.entity.likeCount + it.input.entity.dislikeCount
                }.thenBy { it.input.entity.copilotId },
            ).first()
        }
    }
    internal data class Prepared(
        val documentsById: Map<Long, Document>,
        val families: List<Family>,
        val familyIndexById: Map<Long, Int>,
        val invalidCount: Int,
        val categories: List<String>,
        val activities: List<String>,
        val stages: List<RecommendationStage>,
    ) {
        fun withFeedback(input: RecommendationInput): Prepared {
            val id = input.entity.copilotId
            val previous = documentsById[id] ?: return this
            val updated = previous.copy(input = input)
            val index = familyIndexById.getValue(id)
            val family = families[index]
            return copy(
                documentsById = documentsById + (id to updated),
                families = families.toMutableList().apply {
                    this[index] = Family(family.documents.map { if (it === previous) updated else it })
                },
            )
        }
    }
    private data class Sample(
        val document: Document,
        val member: Member,
        val family: Int,
        val weight: Double,
        val recent: Boolean,
        val quality: Double,
    )

    fun calculate(
        inputs: List<RecommendationInput>,
        levels: List<ArkLevelEntity>,
        query: RecommendationQuery,
        now: LocalDateTime,
    ): RecommendationResult = calculate(prepare(inputs, levels), query, now)

    fun prepare(inputs: List<RecommendationInput>, levels: List<ArkLevelEntity>): Prepared {
        val levelIndex = buildMap {
            levels.forEach { level ->
                listOfNotNull(level.stageId, level.levelId, level.catThree).filter {
                    it.isNotBlank()
                }.forEach { putIfAbsent(it.lowercase(), level) }
            }
        }
        var invalid = 0
        val documents = inputs.filter { it.entity.type == CopilotType.PRTS }.mapNotNull { input ->
            parse(input, levelIndex).also { if (it == null) invalid++ }
        }
        // Anchor clusters avoid transitive chains merging substantially different strategies.
        val families = documents.groupBy { "${it.stage.stageId}|${it.difficulty}|${it.skeleton}" }.values.flatMap { bucket ->
            val clusters = mutableListOf<MutableList<Document>>()
            bucket.sortedBy { it.input.entity.firstUploadTime }.forEach { document ->
                val identities = document.members.map { it.operator.id }.toSet()
                val match = clusters.firstOrNull { cluster ->
                    val anchor = cluster.first().members.map { it.operator.id }.toSet()
                    2.0 * identities.intersect(anchor).size / (identities.size + anchor.size).coerceAtLeast(1) >= 0.85
                }
                if (match == null) clusters.add(mutableListOf(document)) else match.add(document)
            }
            clusters.map { Family(it.toList()) }
        }
        return Prepared(
            documentsById = documents.associateBy { it.input.entity.copilotId },
            families = families,
            familyIndexById = buildMap {
                families.forEachIndexed { index, family -> family.documents.forEach { put(it.input.entity.copilotId, index) } }
            },
            invalidCount = invalid,
            categories = documents.mapNotNull { it.stage.catOne }.filter { it.isNotBlank() }.distinct().sorted(),
            activities = documents.mapNotNull { it.stage.catTwo }.filter { it.isNotBlank() }.distinct().sorted(),
            stages = documents.map {
                RecommendationStage(it.stage.stageId.orEmpty(), listOfNotNull(it.stage.catThree, it.stage.name).joinToString(" "))
            }.distinctBy { it.id }.sortedBy { it.name },
        )
    }

    fun calculate(prepared: Prepared, query: RecommendationQuery, now: LocalDateTime): RecommendationResult {
        val window = if (query.days == 0) 180 else query.days
        val cutoff = now.minusDays(window.toLong())
        val selected = prepared.families.filter { family ->
            val stage = family.documents.first().stage
            val permanent = isPermanent(stage)
            val recent =
                family.firstPublishedAt >= cutoff || family.variants.any { (it.input.latestPositiveAt ?: LocalDateTime.MIN) >= cutoff }
            (query.includeClosed || stage.isOpen != false || permanent) &&
                (query.stageId.isNullOrBlank() || query.stageId == stage.stageId || query.stageId == stage.levelId) &&
                (query.category.isNullOrBlank() || query.category == stage.catOne) &&
                (query.activity.isNullOrBlank() || query.activity == stage.catTwo) &&
                when (query.scope) {
                    RecommendationScope.RECENT -> recent || permanent
                    RecommendationScope.PERMANENT -> permanent && recent
                    RecommendationScope.HISTORY -> query.days == 0 || recent
                }
        }
        val stageFamilies = selected.groupingBy { stageKey(it.documents.first()) }.eachCount()
        val exposure = selected.flatMap { it.variants }.groupBy { "${stageKey(it)}|${ageBucket(it.input.entity.firstUploadTime, now)}" }
            .mapValues { (_, rows) -> rows.map { ln(1.0 + it.input.entity.views.coerceAtLeast(0)) }.sorted().let { it[it.size / 2] } }
        val samples = selected.flatMapIndexed { familyId, family ->
            val variants = family.variants
            variants.flatMap { document ->
                val row = document.input.entity
                val (lower, upper) = wilson(row.likeCount, row.dislikeCount)
                if (upper < 0.6) return@flatMap emptyList()
                val provisional = row.likeCount + row.dislikeCount == 0L
                if (provisional && !query.includeUncertain) return@flatMap emptyList()
                val quality = if (provisional) 0.05 else lower
                val ageHours = ChronoUnit.HOURS.between(family.firstPublishedAt, now).coerceAtLeast(0).toDouble()
                val mature = 0.25 + 0.75 * (ageHours / 72.0).coerceIn(0.0, 1.0)
                val publishAge = ageHours / 24.0
                val feedback = document.input.latestPositiveAt
                val feedbackAge = feedback?.let { ChronoUnit.DAYS.between(it, now).coerceAtLeast(0).toDouble() }
                val verifiedAge = if (lower >= 0.5 && feedbackAge != null) minOf(publishAge, feedbackAge + 90.0) else publishAge
                val recency = 2.0.pow(-verifiedAge / 180.0)
                val baseline = exposure["${stageKey(document)}|${ageBucket(row.firstUploadTime, now)}"] ?: 0.0
                val viewAdjustment = if (baseline <= 0.0) 1.0 else (ln(1.0 + row.views.coerceAtLeast(0)) / baseline).coerceIn(0.9, 1.1)
                val recent = family.firstPublishedAt >= cutoff || (lower >= 0.5 && feedback != null && feedback >= cutoff)
                val weight = quality * mature * recency * viewAdjustment / variants.size / stageFamilies.getValue(stageKey(document))
                document.members.filter { query.includeAlternatives || !it.alternative }.map { member ->
                    Sample(document, member, familyId, weight * member.share, recent, lower)
                }
            }
        }
        val categoryStages = selected.map { it.documents.first() }.groupBy {
            isPermanent(it.stage) to it.stage.catOne.orEmpty()
        }.mapValues { (_, rows) ->
            rows.groupBy { it.stage.catTwo.orEmpty() }.mapValues { (_, stages) -> stages.map(::stageKey).distinct().size }
        }
        val permanentCategories = categoryStages.keys.count { it.first }
        val otherCategories = categoryStages.keys.count { !it.first }
        val availableBudget = (if (permanentCategories > 0) 0.3 else 0.0) + (if (otherCategories > 0) 0.7 else 0.0)
        val recommendations = samples.groupBy { it.member.operator.id }.mapNotNull { (_, evidence) ->
            val stages = evidence.map { stageKey(it.document) }.distinct()
            val recent = evidence.filter { it.recent && it.quality > 0.0 }
            val recentStages = recent.map { stageKey(it.document) }.distinct().size
            val recentFamilies = recent.map { it.family }.distinct().size
            val independent = evidence.groupBy { it.family }.values.map { family -> family.maxBy { it.quality } }
            val (qualityFloor, _) = wilson(
                independent.sumOf { it.document.input.entity.likeCount },
                independent.sumOf { it.document.input.entity.dislikeCount },
            )
            val strong = qualityFloor >= 0.5
            val focused = !query.stageId.isNullOrBlank() || !query.activity.isNullOrBlank()
            val current = strong && recentStages >= (if (focused) 1 else 2) && recentFamilies >= (if (focused) 1 else 3)
            if (query.scope == RecommendationScope.RECENT && !query.includeUncertain && !current) return@mapNotNull null
            val status = when {
                !strong -> "INSUFFICIENT"
                current -> "CURRENT"
                recentFamilies > 0 -> "LIMITED"
                else -> "HISTORICAL"
            }
            val balanced = evidence.map { sample ->
                val stage = sample.document.stage
                val activities = categoryStages.getValue(isPermanent(stage) to stage.catOne.orEmpty())
                val categoryWeight = (if (isPermanent(stage)) 0.3 / permanentCategories else 0.7 / otherCategories) / availableBudget
                sample.copy(weight = sample.weight * categoryWeight / activities.size / activities.getValue(stage.catTwo.orEmpty()))
            }
            val weight = balanced.sumOf { it.weight }
            if (weight <= 0.0) return@mapNotNull null
            OperatorRecommendation(
                operator = evidence.first().member.operator,
                score = (100.0 * weight).coerceAtMost(100.0),
                stageCount = stages.size,
                familyCount = evidence.map { it.family }.distinct().size,
                recentStageCount = recentStages,
                recentFamilyCount = recentFamilies,
                status = status,
                branches = branches(balanced, query.coverage),
                sources = balanced.sortedByDescending { it.weight }.distinctBy { it.family }.take(5).map { sample ->
                    val row = sample.document.input.entity
                    RecommendationSource(
                        id = row.copilotId,
                        title = row.title,
                        stageId = sample.document.stage.stageId.orEmpty(),
                        firstPublishedAt = row.firstUploadTime.toString(),
                        likes = row.likeCount,
                        dislikes = row.dislikeCount,
                        views = row.views,
                        quality = sample.quality,
                        alternative = sample.member.alternative,
                    )
                },
            )
        }.sortedWith(compareByDescending<OperatorRecommendation> { it.score }.thenBy { it.operator.id })
        return RecommendationResult(
            generatedAt = now.toString(),
            operationCount = selected.sumOf { it.documents.size },
            familyCount = selected.size,
            invalidCount = prepared.invalidCount,
            categories = prepared.categories,
            activities = prepared.activities,
            stages = prepared.stages,
            recommendations = recommendations,
        )
    }

    private fun parse(input: RecommendationInput, levels: Map<String, ArkLevelEntity>): Document? {
        val root = runCatching { json.parseToJsonElement(input.entity.content) as? JsonObject }.getOrNull() ?: return null
        val stageName = root.string("stage_name") ?: input.entity.stageName
        val stage =
            levels[input.entity.stageName.lowercase()] ?: levels[stageName.lowercase()]
                ?: ArkLevelEntity(stageId = stageName, catOne = "未知类型", catTwo = stageName)
        val members = mutableListOf<Member>()
        val aliases = mutableMapOf<String, String>()
        val fixed = root.array("opers").mapNotNull { parseMember(it as? JsonObject, root.number("version")) }
        fixed.forEach { member -> aliases[member.operator.name] = member.operator.id }
        members.addAll(fixed)
        root.array("groups").forEach { element ->
            val group = element as? JsonObject ?: return@forEach
            val alternatives = group.array("opers")
            val candidates = alternatives.mapNotNull {
                parseMember(it as? JsonObject, root.number("version"))
            }.distinctBy { it.operator.id }
            if (candidates.isNotEmpty()) {
                members.addAll(candidates.map { it.copy(share = 1.0 / alternatives.size, alternative = true) })
                group.string("name")?.let { aliases[it] = candidates.map { it.operator.id }.sorted().joinToString(",") }
            }
        }
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
        val unique = members.groupBy { it.operator.id }.values.map { candidates -> candidates.maxBy { it.share } }
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
        return Document(input, stage, root.number("difficulty") ?: 0, unique, skeleton, variant)
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
            version == 2 -> operator.modules.getOrNull(rawModule) ?: return null
            else -> rawModule
        }
        val caps = levelCaps(operator.rarity)
        if (elite != null && elite !in caps.indices) return null
        if (level != null && level > (elite?.let { caps[it] } ?: caps.max())) return null
        val skillCount = when {
            operator.rarity <= 2 -> 0
            operator.rarity == 3 -> 1
            operator.rarity == 6 || operator.name == "阿米娅" -> 3
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
            val targets = group.map { it.member.target }.distinct()
            val base = targets.first()
            val operator = group.first().member.operator
            val elites = (targets.map { it.elite } + levelCaps(operator.rarity).indices).distinct()
            val levels = (
                targets.map { it.level } + 1 +
                    listOfNotNull(base.module?.takeIf { it > 0 }?.let { moduleUnlockLevel(operator.rarity) })
                ).distinct()
            val skills = targets.map { it.skillLevel }.distinct()
            // Level resets on promotion; enumerate legal thresholds rather than reusing incomplete source pairs.
            val candidates = elites.flatMap { elite ->
                levels.flatMap { level ->
                    skills.map { skill -> base.copy(elite = elite, level = level, skillLevel = skill) }
                }
            }.filter { isFeasible(it, operator) }
            val covered = candidates.map { candidate ->
                candidate to
                    group.filter { covers(candidate, it.member.target) }.sumOf { it.weight } / branchWeight
            }
            val qualifying = covered.filter { it.second + 1e-9 >= requested }
            // ponytail: pairwise dominance within a discrete branch; prune a sorted frontier if catalog growth makes this costly.
            val minimal = qualifying.filter { (candidate, _) ->
                qualifying.none { (other, _) ->
                    other != candidate &&
                        covers(candidate, other) &&
                        !covers(other, candidate)
                }
            }
            minimal.map { (target, coverage) ->
                TrainingBranch(
                    target,
                    branchWeight / total,
                    coverage,
                    group.filter { it.member.target.elite != null && it.member.target.level != null }.sumOf { it.weight } / branchWeight,
                    group.filter { it.member.target.skillLevel != null }.sumOf { it.weight } / branchWeight,
                    group.filter { it.member.target.module != null }.sumOf { it.weight } / branchWeight,
                )
            }
        }.sortedWith(compareByDescending<TrainingBranch> { it.usageShare }.thenBy { it.target.skill }.thenBy { it.target.module ?: -1 })
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
        private fun stageKey(document: Document) = "${document.stage.stageId}|${document.difficulty}"
        private fun ageBucket(time: LocalDateTime, now: LocalDateTime) = when (ChronoUnit.DAYS.between(time, now)) {
            in Long.MIN_VALUE..7 -> 0
            in 8..30 -> 1
            in 31..180 -> 2
            else -> 3
        }
        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
        private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map { canonical(it) })
            else -> value
        }
    }
}
