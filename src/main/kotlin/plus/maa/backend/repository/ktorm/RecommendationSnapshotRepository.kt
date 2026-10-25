package plus.maa.backend.repository.ktorm

import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Repository
import plus.maa.backend.service.recommendation.EvidenceStage
import plus.maa.backend.service.recommendation.RecommendationEvidence
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Repository
class RecommendationSnapshotRepository(private val jdbi: Jdbi) {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun generatedAt(): LocalDateTime? = jdbi.withHandle<LocalDateTime?, Exception> { generatedAt(it) }

    internal fun loadStages(generatedAt: LocalDateTime): List<EvidenceStage>? {
        val encoded = jdbi.withHandle<String?, Exception> { handle ->
            handle.createQuery(
                "SELECT (evidence -> 'stages')::text FROM recommendation_snapshot WHERE id = 1 AND format_version = :version AND generated_at = :time",
            ).bind("version", FORMAT_VERSION).bind("time", generatedAt).mapTo(String::class.java).findOne().orElse(null)
        } ?: return null
        return json.decodeFromString<List<EvidenceStage>>(encoded)
    }

    internal fun load(generatedAt: LocalDateTime): RecommendationEvidence? {
        val encoded = jdbi.withHandle<String?, Exception> { handle ->
            handle.createQuery(
                "SELECT evidence::text FROM recommendation_snapshot WHERE id = 1 AND format_version = :version AND generated_at = :time",
            ).bind("version", FORMAT_VERSION).bind("time", generatedAt).mapTo(String::class.java).findOne().orElse(null)
        } ?: return null
        return json.decodeFromString<RecommendationEvidence>(encoded)
    }

    /** The transaction lock also coalesces daily builds across application instances. */
    internal fun rebuildIfNeeded(now: LocalDateTime, build: () -> RecommendationEvidence): Boolean =
        jdbi.inTransaction<Boolean, Exception> { handle ->
            handle.createQuery("SELECT pg_advisory_xact_lock(1380270927)").map { _, _ -> Unit }.one()
            val previous = generatedAt(handle)
            if (previous != null && previous.toLocalDate() >= now.toLocalDate()) return@inTransaction false
            val encoded = json.encodeToString(build())
            handle.createUpdate(
                """
                INSERT INTO recommendation_snapshot (id, format_version, generated_at, evidence)
                VALUES (1, :version, :time, :evidence::jsonb)
                ON CONFLICT (id) DO UPDATE SET
                    format_version = EXCLUDED.format_version,
                    generated_at = EXCLUDED.generated_at,
                    evidence = EXCLUDED.evidence
                """.trimIndent(),
            ).bind("version", FORMAT_VERSION).bind("time", now.truncatedTo(ChronoUnit.MICROS)).bind("evidence", encoded).execute()
            true
        }

    private fun generatedAt(handle: Handle): LocalDateTime? = handle.createQuery(
        "SELECT generated_at FROM recommendation_snapshot WHERE id = 1 AND format_version = :version",
    ).bind("version", FORMAT_VERSION).mapTo(LocalDateTime::class.java).findOne().orElse(null)

    private companion object {
        // Bump when the evidence layout or interpretation becomes incompatible.
        const val FORMAT_VERSION = 1
    }
}
