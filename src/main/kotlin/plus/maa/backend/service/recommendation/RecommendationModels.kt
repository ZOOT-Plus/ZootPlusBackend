package plus.maa.backend.service.recommendation

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import kotlinx.serialization.Serializable
import plus.maa.backend.repository.entity.CopilotEntity
import java.time.LocalDateTime

/** RECENT includes ongoing permanent demand; HISTORY explicitly opts into old-only operators. */
enum class RecommendationScope { RECENT, PERMANENT, HISTORY }

data class RecommendationQuery(
    @field:Min(0) @field:Max(3650) val days: Int = 180,
    val scope: RecommendationScope = RecommendationScope.RECENT,
    @field:Size(max = 200) val stageId: String? = null,
    @field:Size(max = 200) val category: String? = null,
    @field:Size(max = 200) val activity: String? = null,
    val includeClosed: Boolean = false,
    val includeAlternatives: Boolean = true,
    val includeUncertain: Boolean = false,
    @field:DecimalMin("0.5") @field:DecimalMax("1.0") val coverage: Double = 0.8,
)

@Serializable
data class RecommendationOperator(
    val id: String,
    val name: String,
    val role: String,
    val rarity: Int,
    val modules: List<Int?> = emptyList(),
)

@Serializable
data class TrainingTarget(
    val elite: Int? = null,
    val level: Int? = null,
    val skill: Int,
    val skillLevel: Int? = null,
    val module: Int? = null,
    /** Reserved for future structured source data; currently always null. */
    val moduleLevel: Int? = null,
)

@Serializable
data class TrainingBranch(
    val target: TrainingTarget,
    val usageShare: Double,
    val coverage: Double,
    val levelDataRatio: Double,
    val skillDataRatio: Double,
    val moduleDataRatio: Double,
)

@Serializable
data class RecommendationSource(
    val id: Long,
    val title: String,
    val stageId: String,
    val firstPublishedAt: String,
    val likes: Long,
    val dislikes: Long,
    val views: Long,
    val quality: Double,
    val alternative: Boolean,
)

@Serializable
data class OperatorRecommendation(
    val operator: RecommendationOperator,
    val score: Double,
    val stageCount: Int,
    val familyCount: Int,
    val recentStageCount: Int,
    val recentFamilyCount: Int,
    val status: String,
    val branches: List<TrainingBranch>,
    val sources: List<RecommendationSource>,
)

@Serializable
data class RecommendationResult(
    val generatedAt: String,
    val algorithmVersion: Int = 1,
    val operationCount: Int,
    val familyCount: Int,
    val invalidCount: Int,
    val categories: List<String>,
    val activities: List<String>,
    val stages: List<RecommendationStage>,
    val recommendations: List<OperatorRecommendation>,
)

@Serializable
data class RecommendationStage(val id: String, val name: String)

internal data class RecommendationInput(
    val entity: CopilotEntity,
    val latestPositiveAt: LocalDateTime? = null,
)
