package plus.maa.backend.service.level.parser

import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.gamedata.ArkTilePos
import plus.maa.backend.service.level.ArkLevelType

/**
 * 集成战略（肉鸽）关卡解析
 * 示例内容
 * ```json
 * {
 *     "code": "ISW-NO",
 *     "height": 7,
 *     "levelId": "obt/roguelike/ro5/level_rogue5_1-4",
 *     "name": "老戏骨",
 *     "stageId": "ro5_e_1_4"
 * }
 * ```
 */
class RoguelikeParser : ArkLevelParser {

    override fun supportType(type: ArkLevelType): Boolean =
        ArkLevelType.ROGUELIKE == type

    override fun parseLevel(
        level: ArkLevel,
        tilePos: ArkTilePos
    ): ArkLevel {
        level.catOne = ArkLevelType.ROGUELIKE.display
        return level
    }
}
