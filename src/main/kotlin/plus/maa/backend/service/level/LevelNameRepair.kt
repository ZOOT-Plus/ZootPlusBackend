package plus.maa.backend.service.level

/**
 * 活动名回填（[ArkLevelService.repairMissingActivityNames]）的触发来源，决定快照与节流的处理策略。
 */
enum class LevelNameRepairSource {
    /**
     * 应用启动后：此时快照尚未抓取或刚抓取，直接用；同一时间窗内只执行一次，
     * 避免应用频繁重启时反复执行回填。
     */
    STARTUP,

    /**
     * 地图数据同步之后：有新地图文件时快照刚刷新过，直接用。
     */
    SYNC,

    /**
     * 每日兜底：强制刷新快照，覆盖「活动名晚于地图文件发布」的窗口期，同时周期性重试
     * 上游尚未补齐的数据（上游补齐后无需任何人工介入即可自愈）。
     */
    DAILY,
}

/**
 * [ArkLevelService.repairMissingActivityNames] 的执行结果。
 */
data class LevelNameRepairStat(
    /** 本轮查询到的空名行数。 */
    val scanned: Int,
    /** 本轮成功回填的行数。 */
    val repaired: Int,
    /**
     * 因并发写入而未回填的行数：查询到空值行之后、写入之前，该行已被另一个回填执行填好
     * （条件更新影响 0 行）。不计入失败——值已经是对的，本轮无需再写。
     */
    val skipped: Int,
    /** 解析后仍无活动名的行数（上游数据缺失，留待下一轮自然重试）。 */
    val stillEmpty: Int,
)
