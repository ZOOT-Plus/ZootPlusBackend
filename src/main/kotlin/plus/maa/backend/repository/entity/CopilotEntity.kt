package plus.maa.backend.repository.entity

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.OneToMany
import org.babyfish.jimmer.sql.Table
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

@Entity
@Table(name = "copilot")
interface CopilotEntity {
    // 迁移时不标记为主键防止生成
    // 自增数字ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val copilotId: Long

    // 关卡名
    val stageName: String

    // 上传者id
    val uploaderId: String

    // 查看次数
    val views: Long

    // 评级
    val ratingLevel: Int

    // 评级比率 十分之一代表半星
    val ratingRatio: Double
    val likeCount: Long
    val dislikeCount: Long

    // 热度
    val hotScore: Double

    // 指定干员
//    @Cascade(["copilotId"], ["copilotId"])
    @OneToMany(mappedBy = "copilot")
    val opers: List<OperatorEntity>

    // 文档字段，用于搜索，提取到Copilot类型上
    val title: String
    val details: String?

    // 首次上传时间
    val firstUploadTime: LocalDateTime

    // 更新时间
    val uploadTime: LocalDateTime

    // 原始数据
    val content: String

    /**
     * 作业状态，后端默认设置为公开以兼容历史逻辑
     * [plus.maa.backend.service.model.CopilotSetStatus]
     */
    val status: CopilotSetStatus

    /**
     * 评论状态
     */
    val commentStatus: CommentStatus

    val delete: Boolean
    val deleteTime: LocalDateTime?
    val notification: Boolean
}
