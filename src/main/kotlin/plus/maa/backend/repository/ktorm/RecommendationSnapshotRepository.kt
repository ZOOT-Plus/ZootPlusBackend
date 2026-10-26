package plus.maa.backend.repository.ktorm

import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val log = KotlinLogging.logger { }
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

    /** 多实例同时重建时只让一个执行；锁跟着连接走，所以构建在事务外，只有写入用短事务。 */
    internal fun rebuildIfNeeded(now: LocalDateTime, build: () -> RecommendationEvidence): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT pg_advisory_lock(:id)").bind("id", LOCK_ID).map { _, _ -> Unit }.one()
            try {
                // 可能刚被其他实例建好
                val previous = generatedAt(handle)
                if (previous != null && previous.toLocalDate() >= now.toLocalDate()) return@withHandle false
                val encoded = json.encodeToString(build())
                val startedAt = System.nanoTime()
                handle.inTransaction<Unit, Exception> { transaction ->
                    transaction.createUpdate(
                        """
                        INSERT INTO recommendation_snapshot (id, format_version, generated_at, evidence)
                        VALUES (1, :version, :time, :evidence::jsonb)
                        ON CONFLICT (id) DO UPDATE SET
                            format_version = EXCLUDED.format_version,
                            generated_at = EXCLUDED.generated_at,
                            evidence = EXCLUDED.evidence
                        """.trimIndent(),
                    ).bind("version", FORMAT_VERSION).bind("time", now.truncatedTo(ChronoUnit.MICROS))
                        .bind("evidence", encoded).execute()
                }
                log.info { "推荐证据已写入：编码后 ${encoded.length / 1024} KB，写库 ${(System.nanoTime() - startedAt) / 1_000_000}ms" }
                true
            } finally {
                // 连接断开时锁随会话释放；归还连接不会清锁，所以失败必须留痕
                runCatching {
                    handle.createQuery("SELECT pg_advisory_unlock(:id)").bind("id", LOCK_ID).map { _, _ -> Unit }.one()
                }.onFailure { log.warn(it) { "推荐证据构建锁释放失败，该连接可能继续持锁" } }
            }
        }

    private fun generatedAt(handle: Handle): LocalDateTime? = handle.createQuery(
        "SELECT generated_at FROM recommendation_snapshot WHERE id = 1 AND format_version = :version",
    ).bind("version", FORMAT_VERSION).mapTo(LocalDateTime::class.java).findOne().orElse(null)

    private companion object {
        // Bump when the evidence layout or interpretation becomes incompatible.
        const val FORMAT_VERSION = 1

        /** 构建互斥锁。 */
        const val LOCK_ID = 1380270927L
    }
}
