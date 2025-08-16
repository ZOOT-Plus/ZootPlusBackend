package plus.maa.backend.repository.entity

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.Table
import org.ktorm.schema.long
import org.ktorm.schema.varchar

interface OperatorEntity : Entity<OperatorEntity> {
    val id: Long
    var copilot: CopilotEntity
    var name: String

    companion object : Entity.Factory<OperatorEntity>()
}

object Operators : Table<OperatorEntity>("copilot_operator") {
    val id = long("id").primaryKey().bindTo { it.id }
    val copilotId = long("copilot_id").references(Copilots) { it.copilot }
    val name = varchar("name").bindTo { it.name }
}

val Database.operators get() = this.sequenceOf(Operators)
