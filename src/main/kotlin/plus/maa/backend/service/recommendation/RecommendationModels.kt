package plus.maa.backend.service.recommendation

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Size
import kotlinx.serialization.Serializable
import plus.maa.backend.repository.entity.CopilotEntity
import java.time.LocalDateTime

/** Daily use, permanent-stage catch-up, and long-term reference from the current snapshot. */
enum class RecommendationScope { RECENT, PERMANENT, HISTORY }

data class RecommendationQuery(
    @field:Schema(allowableValues = ["0", "90", "180", "365"]) val days: Int = 180,
    val scope: RecommendationScope = RecommendationScope.RECENT,
    @field:Size(max = 200) val stageId: String? = null,
    @field:Size(max = 200) val category: String? = null,
    @field:Size(max = 200) val activity: String? = null,
    @field:Schema(allowableValues = ["1", "5", "10"]) val minOperationCount: Int = 5,
    val includeClosed: Boolean = false,
    val includeAlternatives: Boolean = true,
    val includeUncertain: Boolean = false,
    @field:Schema(allowableValues = ["0.6", "0.8", "0.9"]) val coverage: Double = 0.8,
) {
    @get:AssertTrue(message = "时间范围仅支持 90、180、365 天，长期参考另支持 0")
    @get:Schema(hidden = true)
    val isWindowSupported: Boolean
        get() = days in listOf(90, 180, 365) || days == 0

    @get:AssertTrue(message = "培养目标仅支持 0.6、0.8、0.9")
    @get:Schema(hidden = true)
    val isCoverageSupported: Boolean
        get() = coverage in listOf(0.6, 0.8, 0.9)

    @get:AssertTrue(message = "选项最低作业数仅支持 1、5、10")
    @get:Schema(hidden = true)
    val isCountSupported: Boolean
        get() = minOperationCount in listOf(1, 5, 10)
}

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
    val algorithmVersion: Int = 3,
    val operationCount: Int,
    val familyCount: Int,
    val invalidCount: Int,
    val categories: List<String>,
    val activities: List<String>,
    val stages: List<RecommendationStage>,
    val recommendations: List<OperatorRecommendation>,
)

@Serializable
data class RecommendationStage(val id: String, val name: String, val category: String? = null, val activity: String? = null)

internal data class RecommendationInput(
    val entity: CopilotEntity,
    val latestPositiveAt: LocalDateTime? = null,
)
