package plus.maa.backend.repository.entity

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.datetime
import org.ktorm.schema.long
import org.ktorm.schema.text
import org.ktorm.schema.varchar
import java.time.LocalDateTime

interface CommentsAreaEntity : Entity<CommentsAreaEntity> {
    var id: String
    var copilotId: Long
    var fromCommentId: String?
    var uploaderId: String
    var message: String
    var likeCount: Long
    var dislikeCount: Long
    var uploadTime: LocalDateTime
    var topping: Boolean
    var delete: Boolean
    var deleteTime: LocalDateTime?
    var mainCommentId: String?
    var notification: Boolean

    companion object : Entity.Factory<CommentsAreaEntity>()
}

object CommentsAreas : Table<CommentsAreaEntity>("comments_area") {
    val id = varchar("id").primaryKey().bindTo { it.id }
    val copilotId = long("copilot_id").bindTo { it.copilotId }
    val fromCommentId = varchar("from_comment_id").bindTo { it.fromCommentId }
    val uploaderId = varchar("uploader_id").bindTo { it.uploaderId }
    val message = text("message").bindTo { it.message }
    val likeCount = long("like_count").bindTo { it.likeCount }
    val dislikeCount = long("dislike_count").bindTo { it.dislikeCount }
    val uploadTime = datetime("upload_time").bindTo { it.uploadTime }
    val topping = boolean("topping").bindTo { it.topping }
    val delete = boolean("delete").bindTo { it.delete }
    val deleteTime = datetime("delete_time").bindTo { it.deleteTime }
    val mainCommentId = varchar("main_comment_id").bindTo { it.mainCommentId }
    val notification = boolean("notification").bindTo { it.notification }
}

val Database.commentsAreas get() = sequenceOf(CommentsAreas)
