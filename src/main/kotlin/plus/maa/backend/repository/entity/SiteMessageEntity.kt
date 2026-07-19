package plus.maa.backend.repository.entity

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.datetime
import org.ktorm.schema.enum
import org.ktorm.schema.long
import org.ktorm.schema.text
import plus.maa.backend.service.model.SiteMessageType
import java.time.LocalDateTime

interface SiteMessageEntity : Entity<SiteMessageEntity> {
    var id: Long
    var receiverId: Long
    var senderId: Long
    var senderName: String
    var type: SiteMessageType
    var title: String
    var content: String
    var copilotId: Long?
    var readAt: LocalDateTime?
    var createdAt: LocalDateTime

    companion object : Entity.Factory<SiteMessageEntity>()
}

object SiteMessages : Table<SiteMessageEntity>("site_message") {
    val id = long("id").primaryKey().bindTo { it.id }
    val receiverId = long("receiver_id").bindTo { it.receiverId }
    val senderId = long("sender_id").bindTo { it.senderId }
    val senderName = text("sender_name").bindTo { it.senderName }
    val type = enum<SiteMessageType>("type").bindTo { it.type }
    val title = text("title").bindTo { it.title }
    val content = text("content").bindTo { it.content }
    val copilotId = long("copilot_id").bindTo { it.copilotId }
    val readAt = datetime("read_at").bindTo { it.readAt }
    val createdAt = datetime("created_at").bindTo { it.createdAt }
}

val Database.siteMessages get() = sequenceOf(SiteMessages)
