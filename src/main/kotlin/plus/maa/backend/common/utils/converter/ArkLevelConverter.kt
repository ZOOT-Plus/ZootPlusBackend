package plus.maa.backend.common.utils.converter

import org.mapstruct.Mapper
import plus.maa.backend.controller.response.copilot.ArkLevelInfo
import plus.maa.backend.repository.entity.ArkLevelEntity

/**
 * @author dragove
 * created on 2022/12/26
 */
@Mapper(componentModel = "spring")
interface ArkLevelConverter {
    fun convert(arkLevel: ArkLevelEntity): ArkLevelInfo {
        return ArkLevelInfo(
            levelId = arkLevel.levelId ?: "",
            stageId = arkLevel.stageId ?: "",
            catOne = arkLevel.catOne ?: "",
            catTwo = arkLevel.catTwo ?: "",
            catThree = arkLevel.catThree ?: "",
            name = arkLevel.name ?: "",
            width = arkLevel.width,
            height = arkLevel.height,
        )
    }

    fun convert(arkLevels: List<ArkLevelEntity>): List<ArkLevelInfo> {
        return arkLevels.map { convert(it) }
    }
}
