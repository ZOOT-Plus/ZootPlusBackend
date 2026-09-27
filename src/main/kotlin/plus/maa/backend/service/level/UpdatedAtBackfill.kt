package plus.maa.backend.service.level

/**
 * 存量行 `updated_at` 回填的触发来源。
 *
 * 回填是幂等的（只处理 `updated_at IS NULL` 的行），因此可以安全地挂在多个触发点上重复调用。
 */
enum class UpdatedAtBackfillSource {
    /** [org.springframework.boot.context.event.ApplicationReadyEvent]，部署后无需人工操作即完成迁移回填。 */
    STARTUP,

    /** 每日开放状态任务之后。覆盖「启动那次因网络/API 失败而没做完」的情况。 */
    DAILY,
}

/**
 * 一次 `updated_at` 回填的结果。全部有默认值，便于测试与日志打印。
 *
 * @param scanned 待回填（`updated_at IS NULL`）的行数
 * @param inWindow 判定为窗口内（近 [LITE_WINDOW_MONTHS] 个月新增的地图文件）
 * @param outOfWindow 判定为窗口外（3 个月前就存在的地图文件）
 * @param written 实际写入的行数；与 [scanned] 的差额是并发抢先写入（同步任务刚插入的新行）
 */
data class UpdatedAtBackfillStat(
    val scanned: Int = 0,
    val inWindow: Int = 0,
    val outOfWindow: Int = 0,
    val written: Int = 0,
)
