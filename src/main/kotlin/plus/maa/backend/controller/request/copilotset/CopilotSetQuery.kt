package plus.maa.backend.controller.request.copilotset

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import kotlinx.serialization.Serializable
import org.springframework.web.bind.annotation.BindParam

/**
 * @author dragove
 * create on 2024-01-06
 */
@Schema(title = "作业集列表查询接口参数")
@Serializable
data class CopilotSetQuery(
    @field:Schema(title = "页码")
    @field:Positive(message = "页码必须为大于0的数字")
    val page: Int = 1,
    @field:Schema(title = "单页数据量")
    @field:PositiveOrZero(message = "单页数据量必须为大于等于0的数字")
    @field:Max(value = 50, message = "单页大小不得超过50")
    val limit: Int = 10,
    @field:Schema(title = "查询关键词")
    val keyword: String? = null,
    @field:Schema(title = "创建者id")
    val creatorId: String? = null,
    @field:Schema(title = "仅查询关注者的作业集")
    var onlyFollowing: Boolean = false,
    @field:Schema(title = "需要包含的作业id列表")
    val copilotIds: List<Long>? = null,
    @field:Schema(title = "降序排列")
    val desc: Boolean = true,
    @field:Schema(title = "排序字段")
    @field:BindParam("order_by") var orderBy: String? = null,
)
