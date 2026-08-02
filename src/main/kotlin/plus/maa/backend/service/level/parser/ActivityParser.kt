package plus.maa.backend.service.level.parser

import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.gamedata.ArkTilePos
import plus.maa.backend.service.level.ArkGameDataHolder
import plus.maa.backend.service.level.ArkLevelType

/**
 * @author john180
 *
 *
 * Activity level will be tagged like this:<br></br>
 * Activity -> ACT_NAME -> StageCode == activities/ACT_ID/LEVEL_ID<br></br>
 * eg:<br></br>
 * 活动关卡 -> 战地秘闻 -> SW-EV-1 == activities/act4d0/level_act4d0_01<br></br>
 */
class ActivityParser(
    private val dataHolder: ArkGameDataHolder,
) : ArkLevelParser {
    override fun supportType(type: ArkLevelType): Boolean {
        return ArkLevelType.ACTIVITIES == type
    }

    override fun parseLevel(level: ArkLevel, tilePos: ArkTilePos): ArkLevel? {
        level.catOne = ArkLevelType.ACTIVITIES.display

        val stage = dataHolder.findStage(level.levelId!!, tilePos.code, tilePos.stageId!!)
        level.catTwo = stage?.zoneId
            ?.let { dataHolder.findActivityByZoneId(it) }
            ?.name ?: ""
        return level
    }
}
