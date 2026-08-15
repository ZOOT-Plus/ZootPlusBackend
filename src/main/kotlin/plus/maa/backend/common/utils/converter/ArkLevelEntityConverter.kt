package plus.maa.backend.common.utils.converter

import org.springframework.stereotype.Component
import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.ArkLevelEntity

@Component
class ArkLevelEntityConverter {

    fun convertToEntityWithAutoId(arkLevel: ArkLevel): ArkLevelEntity {
        // id 不设置（默认 0），由 repository insert 回填自增主键
        return ArkLevelEntity(
            levelId = arkLevel.levelId,
            stageId = arkLevel.stageId,
            sha = arkLevel.sha,
            catOne = arkLevel.catOne,
            catTwo = arkLevel.catTwo,
            catThree = arkLevel.catThree,
            name = arkLevel.name,
            width = arkLevel.width,
            height = arkLevel.height,
            isOpen = arkLevel.isOpen,
            closeTime = arkLevel.closeTime,
        )
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
}
