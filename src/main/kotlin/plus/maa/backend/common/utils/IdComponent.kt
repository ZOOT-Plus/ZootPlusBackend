package plus.maa.backend.common.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.ktorm.dsl.from
import org.ktorm.dsl.map
import org.ktorm.dsl.max
import org.ktorm.dsl.select
import org.springframework.stereotype.Component
import plus.maa.backend.repository.entity.CollectionMeta
import plus.maa.backend.repository.entity.CopilotSets
import plus.maa.backend.repository.entity.Copilots
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger { }

@Component
class IdComponent(
    private val database: Database,
) {
    private val currentIdMap: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    /**
     * 获取id数据
     * @param meta 集合元数据
     * @return 新的id
     */
    fun <T> getId(meta: CollectionMeta<T>): Long {
        val collectionName = meta.entityClass.simpleName
        val v = currentIdMap[collectionName]
        if (v == null) {
            synchronized(meta.entityClass) {
                val rv = currentIdMap[collectionName]
                if (rv == null) {
                    val maxId = getMaxId(meta.entityClass)
                    val nv = AtomicLong(maxId)
                    log.info { "初始化获取 $collectionName 的最大 id，id: ${nv.get()}" }
                    currentIdMap[collectionName] = nv
                    return nv.incrementAndGet()
                }
                return rv.incrementAndGet()
            }
        }
        return v.incrementAndGet()
    }

    private fun <T> getMaxId(entityClass: Class<T>): Long {
        return when (entityClass.simpleName) {
            "Copilot" -> {
                database.from(Copilots).select(max(Copilots.copilotId)).map { it.getLong(1) }.firstOrNull() ?: 20000L
            }
            "CopilotSet" -> {
                database.from(CopilotSets).select(max(CopilotSets.id)).map { it.getLong(1) }.firstOrNull() ?: 20000L
            }
            else -> 20000L
        }
    }
}
