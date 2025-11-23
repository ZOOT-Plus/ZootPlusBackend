package plus.maa.backend.service.level

import java.util.Locale

enum class ArkLevelType(
    val display: String,
) {
    MAINLINE("主题曲"),
    WEEKLY("资源收集"),
    ACTIVITIES("活动关卡"),
    CAMPAIGN("剿灭作战"),
    MEMORY("悖论模拟"),
    RUNE("危机合约"),
    LEGION("保全派驻"),
    ROGUELIKE("集成战略"),
    TRAINING("训练关卡"), // 实际不进行解析
    SANDBOX("生息演算"), // 实际不进行解析
    UNKNOWN("未知类型"), ;

    companion object {
        fun fromLevelId(levelId: String?): ArkLevelType {
            if (levelId.isNullOrBlank()) {
                return UNKNOWN
            }
            val ids = levelId.lowercase(Locale.getDefault()).split("/").dropLastWhile { it.isEmpty() }.toTypedArray()
            val type = if ((ids[0] == "obt")) ids[1] else ids[0]
            return when (type) {
                "main", "hard" -> MAINLINE
                "weekly", "promote" -> WEEKLY
                "activities" -> ACTIVITIES
                "campaign" -> CAMPAIGN
                "memory" -> MEMORY
                "rune", "crisis" -> RUNE
                "legion" -> LEGION
                "roguelike" -> ROGUELIKE
                "training" -> TRAINING
                "sandbox" -> SANDBOX
                else -> UNKNOWN
            }
        }
    }
}
