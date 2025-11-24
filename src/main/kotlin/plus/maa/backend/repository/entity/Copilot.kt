package plus.maa.backend.repository.entity

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

/**
 * @author LoMu
 * Date 2022-12-25 17:56
 */
@Serializable
class Copilot(
    var id: String? = null,
    // 自增数字ID
    var copilotId: Long? = null,
    // 关卡名
    var stageName: String? = null,
    // 上传者id
    var uploaderId: String? = null,
    // 查看次数
    var views: Long = 0L,
    // 评级
    var ratingLevel: Int = 0,
    // 评级比率 十分之一代表半星
    var ratingRatio: Double = 0.0,
    var likeCount: Long = 0,
    var dislikeCount: Long = 0,

    // 热度
    var hotScore: Double = 0.0,
    // 难度
    var difficulty: Int = 0,
    // 版本号(文档中说明:最低要求 maa 版本号，必选。保留字段)
    var minimumRequired: String? = null,
    // 指定干员
    var opers: List<Operators>? = null,
    // 群组
    var groups: List<Groups>? = null,
    // 战斗中的操作
    var actions: List<Action>? = null,
    // 描述
    var doc: Doc?,
    // 首次上传时间
    @Contextual
    var firstUploadTime: LocalDateTime? = null,
    // 更新时间
    @Contextual
    var uploadTime: LocalDateTime? = null,
    // 原始数据
    var content: String? = null,
    /**
     * 作业状态，后端默认设置为公开以兼容历史逻辑
     * [plus.maa.backend.service.model.CopilotSetStatus]
     */
    var status: CopilotSetStatus = CopilotSetStatus.PUBLIC,
    @Transient
    var commentStatus: CommentStatus? = CommentStatus.ENABLED,
    @Transient
    var delete: Boolean = false,
    @Transient
    var deleteTime: LocalDateTime? = null,
    @Transient
    var notification: Boolean? = null,
) {
    @Serializable
    data class OperationGroup(
        // 干员名
        var name: String? = null,
        // 技能序号。可选，默认 1
        var skill: Int = 1,
        // 技能用法。可选，默认 0
        var skillUsage: Int = 0,
    )

    @Serializable
    data class Operators(
        // 干员名
        var name: String? = null,
        // 技能序号。可选，默认 1
        var skill: Int = 1,
        // 技能用法。可选，默认 0
        var skillUsage: Int = 0,
        var requirements: Requirements = Requirements(),
    ) {
        @Serializable
        data class Requirements(
            // 精英化等级。可选，默认为 0, 不要求精英化等级
            var elite: Int = 0,
            // 干员等级。可选，默认为 0
            var level: Int = 0,
            // 技能等级。可选，默认为 0
            var skillLevel: Int = 0,
            // 模组编号。可选，默认为 0
            var module: Int = 0,
            // 潜能要求。可选，默认为 0
            var potentiality: Int = 0,
        )
    }

    @Serializable
    data class Groups(
        // 群组名
        var name: String? = null,
        val opers: List<OperationGroup>? = null,
        var operators: List<String>? = null,
    )

    @Serializable
    data class Action(
        // 操作类型，可选，默认 "Deploy"
        var type: String? = "Deploy",
        var kills: Int? = 0,
        var costs: Int? = 0,
        var costChanges: Int? = 0,
        // 默认 -1
        var cooling: Int? = -1,
        var name: String? = null,
        // 部署干员的位置。
        var location: Array<Int>?,
        // 部署干员的干员朝向 中英文皆可
        var direction: String? = "None",
        // 修改技能用法。当 type 为 "技能用法" 时必选
        var skillUsage: Int? = 0,
        // 前置延时
        var preDelay: Int? = 0,
        // 后置延时
        var postDelay: Int? = 0,
        // maa:保留字段，暂未实现
        var timeout: Int? = 0,
        // 描述
        var doc: String? = "",
        var docColor: String? = "Gray",
    )

    @Serializable
    data class Doc(
        var title: String,
        var titleColor: String? = "Gray",
        var details: String? = "",
        var detailsColor: String? = "Gray",
    )
}
