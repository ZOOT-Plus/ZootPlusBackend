package plus.maa.backend.repository.entity

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.datetime
import org.ktorm.schema.double
import org.ktorm.schema.enum
import org.ktorm.schema.int
import org.ktorm.schema.long
import org.ktorm.schema.text
import org.ktorm.schema.varchar
import plus.maa.backend.service.model.CommentStatus
import plus.maa.backend.service.model.CopilotSetStatus
import java.time.LocalDateTime

interface CopilotEntity : Entity<CopilotEntity> {
    // 自增数字ID
    var copilotId: Long

    // 关卡名
    var stageName: String

    // 上传者id
    var uploaderId: String

    // 查看次数
    var views: Long

    // 评级
    var ratingLevel: Int

    // 评级比率 十分之一代表半星
    var ratingRatio: Double
    var likeCount: Long
    var dislikeCount: Long

    // 热度
    var hotScore: Double

    // 文档字段，用于搜索，提取到Copilot类型上
    var title: String
    var details: String?

    // 首次上传时间
    var firstUploadTime: LocalDateTime

    // 更新时间
    var uploadTime: LocalDateTime

    // 原始数据
    var content: String

    /**
     * 作业状态，后端默认设置为公开以兼容历史逻辑
     * [plus.maa.backend.service.model.CopilotSetStatus]
     */
    var status: CopilotSetStatus

    /**
     * 评论状态
     */
    var commentStatus: CommentStatus

    var delete: Boolean
    var deleteTime: LocalDateTime?
    var notification: Boolean

    companion object : Entity.Factory<CopilotEntity>()
}

object Copilots : Table<CopilotEntity>("copilot") {
    val copilotId = long("copilot_id").primaryKey().bindTo { it.copilotId }
    val stageName = varchar("stage_name").bindTo { it.stageName }
    val uploaderId = varchar("uploader_id").bindTo { it.uploaderId }
    val views = long("views").bindTo { it.views }
    val ratingLevel = int("rating_level").bindTo { it.ratingLevel }
    val ratingRatio = double("rating_ratio").bindTo { it.ratingRatio }
    val likeCount = long("like_count").bindTo { it.likeCount }
    val dislikeCount = long("dislike_count").bindTo { it.dislikeCount }
    val hotScore = double("hot_score").bindTo { it.hotScore }
    val title = varchar("title").bindTo { it.title }
    val details = text("details").bindTo { it.details }
    val firstUploadTime = datetime("first_upload_time").bindTo { it.firstUploadTime }
    val uploadTime = datetime("upload_time").bindTo { it.uploadTime }
    val content = text("content").bindTo { it.content }
    val status = enum<CopilotSetStatus>("status").bindTo { it.status }
    val commentStatus = enum<CommentStatus>("comment_status").bindTo { it.commentStatus }
    val delete = boolean("delete").bindTo { it.delete }
    val deleteTime = datetime("delete_time").bindTo { it.deleteTime }
    val notification = boolean("notification").bindTo { it.notification }
}

val Database.copilots get() = sequenceOf(Copilots)
