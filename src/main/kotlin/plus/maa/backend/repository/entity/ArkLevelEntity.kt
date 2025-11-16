package plus.maa.backend.repository.entity

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.boolean
import org.ktorm.schema.datetime
import org.ktorm.schema.int
import org.ktorm.schema.long
import org.ktorm.schema.varchar
import java.time.LocalDateTime

interface ArkLevelEntity : Entity<ArkLevelEntity> {
    var id: Long
    var levelId: String?
    var stageId: String?
    var sha: String
    var catOne: String?
    var catTwo: String?
    var catThree: String?
    var name: String?
    var width: Int
    var height: Int
    var isOpen: Boolean?
    var closeTime: LocalDateTime?

    companion object : Entity.Factory<ArkLevelEntity>() {
        val EMPTY: ArkLevelEntity
            get() = ArkLevelEntity {
                this.sha = ""
                this.width = 0
                this.height = 0
            }
    }
}

object ArkLevels : Table<ArkLevelEntity>("ark_level") {
    val id = long("id").primaryKey().bindTo { it.id }
    val levelId = varchar("level_id").bindTo { it.levelId }
    val stageId = varchar("stage_id").bindTo { it.stageId }
    val sha = varchar("sha").bindTo { it.sha }
    val catOne = varchar("cat_one").bindTo { it.catOne }
    val catTwo = varchar("cat_two").bindTo { it.catTwo }
    val catThree = varchar("cat_three").bindTo { it.catThree }
    val name = varchar("name").bindTo { it.name }
    val width = int("width").bindTo { it.width }
    val height = int("height").bindTo { it.height }
    val isOpen = boolean("is_open").bindTo { it.isOpen }
    val closeTime = datetime("close_time").bindTo { it.closeTime }
}

val Database.arkLevels get() = sequenceOf(ArkLevels)
