package plus.maa.backend.repository.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import plus.maa.backend.common.model.CopilotSetType
import plus.maa.backend.service.model.CopilotSetStatus
import java.io.Serializable
import java.time.LocalDateTime

/**
 * 作业集数据
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
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
    val createTime: LocalDateTime,
    /**
     * 更新时间
     */
    var updateTime: LocalDateTime,
    /**
     * 作业状态
     * [plus.maa.backend.service.model.CopilotSetStatus]
     */
    var status: CopilotSetStatus,
    @field:JsonIgnore var delete: Boolean = false,
    @field:JsonIgnore var deleteTime: LocalDateTime? = null,
) : Serializable, CopilotSetType
