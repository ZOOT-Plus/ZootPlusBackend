@file:UseSerializers(EvidenceTimeSerializer::class)

package plus.maa.backend.service.recommendation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDateTime

/** Only calculation evidence survives the daily build; source JSON and fingerprints do not. */
@Serializable
internal data class RecommendationEvidence(
    val operators: List<RecommendationOperator>,
    val stages: List<EvidenceStage>,
    val families: List<EvidenceFamily>,
    val invalidCount: Int,
)

@Serializable
internal data class EvidenceStage(
    val id: String,
    val levelId: String?,
    val name: String,
    val category: String?,
    val activity: String?,
    val isOpen: Boolean?,
    val permanent: Boolean,
)

@Serializable
internal data class EvidenceFamily(
    val stage: Int,
    val difficulty: Int,
    val firstPublishedAt: LocalDateTime,
    val operationCount: Int,
    val variants: List<EvidenceVariant>,
)

@Serializable
internal data class EvidenceVariant(
    val id: Long,
    val title: String,
    val firstPublishedAt: LocalDateTime,
    val latestPositiveAt: LocalDateTime?,
    val views: Long,
    val likes: Long,
    val dislikes: Long,
    val members: List<EvidenceMember>,
)

@Serializable
internal data class EvidenceMember(
    val operator: Int,
    val target: TrainingTarget,
    val share: Double,
    val alternative: Boolean,
)

/** Preserve fractional seconds so persisted evidence produces the same result as a fresh build. */
internal object EvidenceTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("EvidenceTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}
