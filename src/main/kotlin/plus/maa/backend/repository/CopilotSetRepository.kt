package plus.maa.backend.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import plus.maa.backend.repository.entity.CopilotSet

/**
 * @author dragove
 * create on 2024-01-01
 */
interface CopilotSetRepository : MongoRepository<CopilotSet, Long> {
    @Query(
        """
            {
                "${'$'}or": [
                    {"name": {'${'$'}regex': ?0 ,'${'$'}options':'i'}},
                    {"description": {'${'$'}regex': ?0,'${'$'}options':'i' }}
                ]
            }
            
            """,
    )
    fun findByKeyword(keyword: String, pageable: Pageable): Page<CopilotSet>

    fun findAllByDeleteIsFalse(pageable: Pageable): Page<CopilotSet>
}
