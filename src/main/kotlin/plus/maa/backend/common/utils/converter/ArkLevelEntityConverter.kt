package plus.maa.backend.common.utils.converter

import org.springframework.stereotype.Component
import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.ArkLevelEntity

@Component
class ArkLevelEntityConverter {

    fun convertToEntity(arkLevel: ArkLevel): ArkLevelEntity {
        return ArkLevelEntity {
            this.id = arkLevel.id ?: ""
            this.levelId = arkLevel.levelId
            this.stageId = arkLevel.stageId
            this.sha = arkLevel.sha
            this.catOne = arkLevel.catOne
            this.catTwo = arkLevel.catTwo
            this.catThree = arkLevel.catThree
            this.name = arkLevel.name
            this.width = arkLevel.width
            this.height = arkLevel.height
            this.isOpen = arkLevel.isOpen
            this.closeTime = arkLevel.closeTime
        }
    }

    fun convertFromEntity(entity: ArkLevelEntity): ArkLevel {
        return ArkLevel(
            id = entity.id,
            levelId = entity.levelId,
            stageId = entity.stageId,
            sha = entity.sha,
            catOne = entity.catOne,
            catTwo = entity.catTwo,
            catThree = entity.catThree,
            name = entity.name,
            width = entity.width,
            height = entity.height,
            isOpen = entity.isOpen,
            closeTime = entity.closeTime,
        )
    }

    fun convertFromEntities(entities: List<ArkLevelEntity>): List<ArkLevel> {
        return entities.map { convertFromEntity(it) }
    }

    fun convertToEntities(arkLevels: List<ArkLevel>): List<ArkLevelEntity> {
        return arkLevels.map { convertToEntity(it) }
    }
}
