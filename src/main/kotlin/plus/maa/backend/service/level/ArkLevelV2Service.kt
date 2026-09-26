package plus.maa.backend.service.level

import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import plus.maa.backend.config.CacheConfig
import plus.maa.backend.controller.response.copilot.ArkLevelInfoV2
import plus.maa.backend.controller.response.copilot.LevelPayload
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * `/arknights/level/v2` 的窗口长度：近 3 个日历月。
 *
 * 两个使用方必须共用同一个常量，否则「查得快照」与「回填判定」会各用一套边界：
 * - [ArkLevelV2Service] 的 lite 查询（`updated_at >= now() - interval '3 months'`）
 * - [ArkLevelService.backfillUpdatedAt] 对存量行的窗口内/外分类
 */
internal const val LITE_WINDOW_MONTHS = 3

/**
 * `/arknights/level/v2` 的读路径：取某个变体的快照，并给出该快照的内容版本号。
 *
 * 两条设计约束（方案 §2、§3.3）：
 *
 * 1. **版本号与行数据必须来自同一次计算**（[snapshot] 内先查行、再用这些行算摘要）。版本号相同即保证
 *    内容相同，客户端据此把 `?v=<版本>` 当不可变资源长期缓存。若版本号另算一处（另一条查询或另一层
 *    缓存），就会出现「新版本号配旧数据」的错配，而客户端会把它当 immutable 永久缓存——错误被放大成
 *    长期脏数据。
 * 2. **`lite` 与 full 的行集合不同**，各有独立版本号（共用会让彼此无谓失效）；`withSize` 只决定是否
 *    携带 `width`/`height`，不改变行集合，因此与同变体的 no-size 天然共用版本号（同一份行数据算出）。
 *
 * 缓存（Caffeine，`expireAfterWrite=300s`，缓存名由 [CacheConfig] 兜底注册）：**缓存键只含 `lite`**，
 * 存储形态是 [LevelSnapshot]（保留 width/height 的行数据），`withSize` 投影在缓存之外做。若把 withSize
 * 也纳入缓存键，`/version`（no-size）与内容端点（with-size）会命中**独立过期**的两份快照：同步改表后
 * 一个条目热、另一个冷的过渡窗内，二者给出互相矛盾的版本号，客户端反复重取直到条目收敛。
 *
 * **勿在类内包一层便捷方法转调 [snapshot]**：`@Cacheable` 走代理，同类内部调用不生效，缓存会静默失效
 * ——`snapshot` 必须由 controller 直接调用。
 *
 * 同步任务最快 10 分钟才有新数据，5 分钟缓存最多让客户端晚 5 分钟看到；`/version` 与内容端点以同一
 * 个 `lite` 为键命中同一快照，给出的版本号不可能互相矛盾。
 */
@Service
class ArkLevelV2Service(
    private val arkLevelRepo: ArkLevelRepository,
) {
    /**
     * 取变体快照（行数据 + 版本号）；响应体由 [LevelSnapshot.toPayload] 投影。
     *
     * @param lite true 时只返回近 [LITE_WINDOW_MONTHS] 个月同步进来的活动关卡（见方案 §4）
     */
    @Cacheable(CacheConfig.ARK_LEVEL_SNAPSHOTS_V2)
    fun snapshot(lite: Boolean): LevelSnapshot {
        val rows = if (lite) {
            arkLevelRepo.findAllUpdatedSince(ArkLevelType.ACTIVITIES.display, liteWindowStart())
        } else {
            arkLevelRepo.findAllOrdered()
        }
        return LevelSnapshot(version = digest(rows), rows = rows)
    }

    companion object {
        /**
         * lite 窗口的起始时刻（`now() - 3 months`）。
         *
         * 用日历月（[LocalDateTime.minusMonths]）而非固定 90 天，与 SQL 的 `interval '3 months'` 同义。
         * 窗口起点随时间前移，故每次查询都重算，不能缓存为常量。
         */
        fun liteWindowStart(): LocalDateTime = LocalDateTime.now().minusMonths(LITE_WINDOW_MONTHS.toLong())

        /** 字段分隔符。U+0000 在关卡数据的文本列里不可能出现，故拼接可无歧义还原。 */
        private const val FIELD_SEPARATOR = "\u0000"

        /**
         * 对行集合算内容摘要（32 位 md5 hex）。
         *
         * 摘要的语义是「**响应体**相同 ⇒ 版本号相同」：客户端据此把 `?v=<版本>` 当不可变资源长期缓存，
         * 所以摘要必须**恰好覆盖 `ArkLevelInfoV2` 的字段**，多一个少一个都是缺陷——
         * - 少一个：该字段的变化不被察觉，客户端永远拿着旧值（版本号没变 ⇒ 永不重取）；
         * - 多一个：字段变化时响应体逐字节相同，版本号却变了，全量客户端白重下整份 payload，
         *   且 ETag 条件请求从 304 退化成 200。`isOpen`/`closeTime` 正属此类（见下）。
         *
         * 因此新增 DTO 字段时必须同步加到这里，`digestCoversEveryFieldTheResponseCarries` 会在漏加时失败。
         *
         * 不含 `isOpen`/`closeTime`：这两列是服务端内部状态（只有 `CopilotScoreRefreshTask` 使用），
         * 不出现在任何响应变体里，改它们不改变响应体。
         *
         * 库里的 NULL 与空串按响应体的口径一并归一成空串（DTO 映射也是这么做的）：不归一化会让这类
         * 无感差异白白让所有客户端重下一次。
         *
         * **不含 `updatedAt`**：同样不出现在响应里，且存量回填会一次性改动全部行，把它算进去只会让所有
         * 客户端的本地缓存失效一次。
         *
         * 行顺序参与摘要，因此调用方取行必须带确定性排序（[ArkLevelRepository.findAllOrdered] 等）。
         */
        private fun digest(rows: List<ArkLevelEntity>): String {
            val md5 = MessageDigest.getInstance("MD5")
            rows.forEach { row ->
                md5.update(encode(row).toByteArray(StandardCharsets.UTF_8))
            }
            return md5.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * 把一行的「响应可见字段」拼成无歧义的一段文本。
         *
         * 字段清单**必须与 `ArkLevelInfoV2` 一致**：`width`/`height` 虽然在 no-size 变体里被省略，
         * 但它们仍是该 DTO 的字段（`withSize=true` 时会返回），故保留。
         */
        private fun encode(row: ArkLevelEntity): String = buildString {
            fun field(value: Any?) {
                append(value ?: "")
                append(FIELD_SEPARATOR)
            }
            field(row.levelId)
            field(row.stageId)
            field(row.catOne)
            field(row.catTwo)
            field(row.catThree)
            field(row.name)
            field(row.width)
            field(row.height)
        }
    }
}

/**
 * 某个 `lite` 变体的快照：一份行数据 + 由这批行算出的内容版本号。
 *
 * 这是缓存（[ArkLevelV2Service.snapshot]）的存储形态——**缓存键只含 lite**，withSize 的取舍在缓存
 * 之外由 [toPayload] 投影，因此同变体的大小两份响应永远共用同一版本号。行数据必须保留 width/height
 * （DTO 省略后无法从缓存条目恢复），这也是快照存实体而非 DTO 的原因。
 */
data class LevelSnapshot(val version: String, val rows: List<ArkLevelEntity>) {

    /**
     * 把快照投影成响应体。
     *
     * @param withSize 是否携带 `width`/`height`；false 时二者为 null，由全局 `explicitNulls = false`
     *   省略键（不是 `"width": null`）
     */
    fun toPayload(withSize: Boolean): LevelPayload = LevelPayload(
        version = version,
        levels = rows.map { it.toDto(withSize) },
    )

    private fun ArkLevelEntity.toDto(withSize: Boolean): ArkLevelInfoV2 = ArkLevelInfoV2(
        // 与 v1 的 ArkLevelConverter 一致：库里可空的列在响应里退化为空串
        levelId = levelId ?: "",
        stageId = stageId ?: "",
        catOne = catOne ?: "",
        catTwo = catTwo ?: "",
        catThree = catThree ?: "",
        name = name ?: "",
        width = if (withSize) width else null,
        height = if (withSize) height else null,
    )
}
