package plus.maa.backend.repository.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import plus.maa.backend.common.model.CopilotSetType
import plus.maa.backend.service.model.CopilotSetStatus
import java.io.Serializable
import java.time.LocalDateTime

/**
 * 作业集数据
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@Document("maa_copilot_set")
data class CopilotSet(
    /**
     * 作业集id
     */
    @field:Id val id: Long = 0,
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
    // 查看次数
    @Indexed
    var views: Long = 0L,
    // 热度
    @Indexed
    var hotScore: Double = 0.0,
    @field:JsonIgnore var delete: Boolean = false,
    @field:JsonIgnore var deleteTime: LocalDateTime? = null,
) : Serializable, CopilotSetType {
    companion object {
        @field:Transient
        val meta = CollectionMeta(
            { obj: CopilotSet -> obj.id },
            "id",
            CopilotSet::class.java,
        )
    }
}
