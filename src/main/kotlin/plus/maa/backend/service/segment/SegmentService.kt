package plus.maa.backend.service.segment

import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.entity.filter
import org.ktorm.entity.forEach
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import org.wltea.analyzer.cfg.Configuration
import org.wltea.analyzer.cfg.DefaultConfig
import org.wltea.analyzer.core.IKSegmenter
import org.wltea.analyzer.dic.Dictionary
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.repository.entity.copilots
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class SegmentService(
    private val database: Database,
    private val ctx: ApplicationContext,
    private val properties: MaaCopilotProperties,
    // temporary fix for openapi generation
    @param:Value($$"${maa-copilot.segment.enabled:true}")
    private val segmentEnabled: Boolean,
) : InitializingBean {

    private val log = KotlinLogging.logger { }

    private val cfg: Configuration = DefaultConfig.getInstance().apply {
        setUseSmart(false)
        Dictionary.initial(this)
        ctx.getResource(properties.segmentInfo.path).inputStream.bufferedReader().use { r ->
            Dictionary.getSingleton().addWords(r.readLines())
        }
    }

    fun getSegment(vararg content: String?): List<String> {
        val set = HashSet<String>()
        content.forEach {
            if (it.isNullOrBlank()) return@forEach
            val helper = IKSegmenter(StringReader(it), cfg)
            while (true) {
                val lex = helper.next() ?: break
                set.add(lex.lexemeText)
            }
        }
        return set.filter {
            it.isNotBlank() && it !in properties.segmentInfo.filteredWordInfo
        }
    }

    companion object {
        // copilotId -> segment list
        private val INDEX = ConcurrentHashMap<String, MutableSet<Long>>()
    }

    fun updateIndex(id: Long, vararg content: String?) {
        getSegment(*content).forEach { word ->
            INDEX.computeIfAbsent(word) {
                HashSet()
            }.add(id)
        }
    }

    fun removeIndex(id: Long, vararg content: String?) {
        getSegment(*content).forEach { word ->
            INDEX[word]?.remove(id)
        }
    }

    fun fetchIndexInfo(word: String) = INDEX.getOrDefault(word, emptySet())

    override fun afterPropertiesSet() {
        if (!segmentEnabled) {
            return
        }
        val segUpdateAt = Instant.now()
        log.info { "Segments updating start at: $segUpdateAt" }

        // small data, fetch all infzo
        database.copilots.filter {
            it.delete eq false
        }.forEach {
            updateIndex(it.copilotId, it.title, it.details)
        }

        log.info { "Segments updated: ${INDEX.size}" }
    }
}
