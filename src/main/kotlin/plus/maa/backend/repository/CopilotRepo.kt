package plus.maa.backend.repository

import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.springframework.stereotype.Repository
import plus.maa.backend.repository.entity.copilots

@Repository
class CopilotRepo(
    val database: Database
) {

    fun getById(id: Long) =
        database.copilots.filter { it.copilotId eq id }.firstOrNull()

    fun getNotDeletedQuery() =
        database.copilots.filter { it.delete eq false }
}
