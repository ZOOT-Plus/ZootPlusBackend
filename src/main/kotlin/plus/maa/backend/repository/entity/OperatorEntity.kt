package plus.maa.backend.repository.entity

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.GeneratedValue
import org.babyfish.jimmer.sql.GenerationType
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.IdView
import org.babyfish.jimmer.sql.JoinColumn
import org.babyfish.jimmer.sql.ManyToOne
import org.babyfish.jimmer.sql.Table

@Entity
@Table(name = "copilot_operator")
interface OperatorEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long
    @IdView
    val copilotId: Long
    @ManyToOne
    @JoinColumn(name = "copilot_id")
    val copilot: CopilotEntity
    val name: String
}
