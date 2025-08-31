package plus.maa.backend.repository.ktorm

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.add
import org.ktorm.entity.count
import org.ktorm.entity.sequenceOf
import org.ktorm.entity.toList
import org.ktorm.entity.update
import org.ktorm.schema.Table

abstract class KtormRepository<E : Entity<E>, T : Table<E>>(
    protected val database: Database,
    protected val table: T,
) {
    protected val entities get() = database.sequenceOf(table)

    abstract fun findById(id: Any): E?

    open fun findAll(): List<E> {
        return entities.toList()
    }

    open fun save(entity: E): E {
        return if (isNewEntity(entity)) {
            entities.add(entity)
            entity
        } else {
            entities.update(entity)
            entity
        }
    }

    abstract fun deleteById(id: Any): Boolean

    open fun count(): Long {
        return entities.count().toLong()
    }

    abstract fun existsById(id: Any): Boolean

    protected abstract fun getIdColumn(entity: E): Any
    protected abstract fun isNewEntity(entity: E): Boolean
}
