package plus.maa.backend.repository.entity

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import plus.maa.backend.common.model.CopilotSetType
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

/**
 * 作业集数据
 */
@Serializable
data class CopilotSet(
    /**
     * 作业集id
     */
    val id: Long = 0,
    /**
     * 作业集名称
     */
    var name: String,
    /**
     * 额外描述
     */
    var description: String,
    /**
     * 作业id列表
     * 使用 list 保证有序
     * 作业添加时应当保证唯一
     */
    override var copilotIds: MutableList<Long>,
    /**
     * 上传者id
     */
    val creatorId: String,
    /**
     * 创建时间
     */
    @Contextual
    val createTime: LocalDateTime,
    /**
     * 更新时间
     */
    @Contextual
    var updateTime: LocalDateTime,
    /**
     * 作业状态
     * [plus.maa.backend.service.model.CopilotSetStatus]
     */
    var status: CopilotSetStatus,
    @Transient var delete: Boolean = false,
    @Transient var deleteTime: LocalDateTime? = null,
) : CopilotSetType
