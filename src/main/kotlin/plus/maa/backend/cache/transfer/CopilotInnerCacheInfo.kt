package plus.maa.backend.cache.transfer

import plus.maa.backend.repository.entity.CopilotEntity
import java.util.concurrent.atomic.AtomicLong

data class CopilotInnerCacheInfo(
    val info: CopilotEntity,
    val view: AtomicLong = AtomicLong(info.views),
)
