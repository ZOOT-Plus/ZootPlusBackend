package plus.maa.backend.service.level.parser

import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.gamedata.ArkTilePos
import plus.maa.backend.service.level.ArkGameDataHolder
import plus.maa.backend.service.level.ArkLevelType

/**
 * @author john180
 *
 *
 * Weekly level will be tagged like this:<br></br>
 * WEEKLY -> WEEKLY_ZONE_NAME -> StageCode == obt/weekly/LEVEL_ID<br></br>
 * eg:<br></br>
 * 资源收集 -> 空中威胁 -> CA-5 == obt/weekly/level_weekly_fly_5<br></br>
 * 资源收集 -> 身先士卒 -> PR-D-2 == obt/promote/level_promote_d_2<br></br>
 */

class WeeklyParser(
    private val dataHolder: ArkGameDataHolder,
) : ArkLevelParser {
    override fun supportType(type: ArkLevelType): Boolean {
        return ArkLevelType.WEEKLY == type
    }

    override fun parseLevel(level: ArkLevel, tilePos: ArkTilePos): ArkLevel? {
        level.catOne = ArkLevelType.WEEKLY.display

        val zone = dataHolder.findZone(level.levelId!!, tilePos.code, tilePos.stageId!!)
            ?: return null

        level.catTwo = zone.zoneNameSecond
        return level
    }
}
