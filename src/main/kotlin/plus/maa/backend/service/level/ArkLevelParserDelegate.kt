package plus.maa.backend.service.level

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.lang.Nullable
import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.gamedata.ArkTilePos
import plus.maa.backend.service.level.parser.ActivityParser
import plus.maa.backend.service.level.parser.CampaignParser
import plus.maa.backend.service.level.parser.LegionParser
import plus.maa.backend.service.level.parser.MainlineParser
import plus.maa.backend.service.level.parser.MemoryParser
import plus.maa.backend.service.level.parser.RoguelikeParser
import plus.maa.backend.service.level.parser.RuneParser
import plus.maa.backend.service.level.parser.UnknownParser
import plus.maa.backend.service.level.parser.WeeklyParser

/**
 * @author john180
 */
class ArkLevelParserDelegate(holder: ArkGameDataHolder) {
    private val log = KotlinLogging.logger { }
    private val parsers = listOf(
        ActivityParser(holder),
        CampaignParser(),
        LegionParser(holder),
        MainlineParser(holder),
        MemoryParser(holder),
        RuneParser(holder),
        UnknownParser(),
        WeeklyParser(holder),
        RoguelikeParser(),
    )

    /**
     * 具体地图信息生成规则见
     * [GameDataParser](https://github.com/MaaAssistantArknights/MaaCopilotServer/blob/main/src/MaaCopilotServer.GameData/GameDataParser.cs)
     * 尚未全部实现 <br></br>
     * TODO 完成剩余字段实现
     */
    @Nullable
    fun parseLevel(tilePos: ArkTilePos, sha: String): ArkLevel? {
        val level = ArkLevel(
            levelId = tilePos.levelId,
            stageId = tilePos.stageId,
            sha = sha,
            catThree = tilePos.code,
            name = tilePos.name,
            width = tilePos.width,
            height = tilePos.height,
        )
        return parseLevel(level, tilePos)
    }

    private fun parseLevel(level: ArkLevel, tilePos: ArkTilePos): ArkLevel? {
        val type = ArkLevelType.fromLevelId(level.levelId)
        if (ArkLevelType.UNKNOWN == type) {
            log.warn { "[PARSER]未知关卡类型:${level.levelId}" }
            return null
        }
        val parser = parsers.firstOrNull { it.supportType(type) }
        if (parser == null) {
            // 类型存在但无对应Parser直接跳过
            return ArkLevel.EMPTY
        }
        return parser.parseLevel(level, tilePos)
    }
}
