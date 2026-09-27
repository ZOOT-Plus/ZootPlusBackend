package plus.maa.backend.repository.entity

import java.time.LocalDateTime

/**
 * 关卡表 ark_level 实体。
 *
 * - 属性保持 var：服务层（updateLevelsOfTypeInBatch 的 block）与测试依赖原地修改。
 * - id 为自增主键：id=0 表示未持久化（INSERT 时由序列生成）；显式非 0 id 按给定值插入（基线行为）。
 */
data class ArkLevelEntity(
    var id: Long = 0,
    var levelId: String? = null,
    var stageId: String? = null,
    var sha: String = "",
    var catOne: String? = null,
    var catTwo: String? = null,
    var catThree: String? = null,
    var name: String? = null,
    var width: Int = 0,
    var height: Int = 0,
    var isOpen: Boolean? = null,
    var closeTime: LocalDateTime? = null,
    /**
     * 该行从上游同步进来的时刻，供 `/arknights/level/v2` 的 lite 变体判定窗口。
     *
     * 默认值为构造时刻：新行（如 parser 产出的实体）据此获得写入时间，从库里读回的行沿用库中原值。
     * 可空是因为存量行为 NULL（迁移不给默认值，含义是「尚未回填」，见 V2 迁移注释）。
     *
     * 该列**只出现在 INSERT 的列清单里，不在 upsert 的 DO UPDATE SET 里**：`updateLevelsOfTypeInBatch`
     * 的整批全列覆盖会让 2177 行活动关卡每轮续期，lite 直接失效，必须在 SQL 层而非调用方约束住。
     */
    var updatedAt: LocalDateTime? = LocalDateTime.now(),
) {
    companion object {
        val EMPTY: ArkLevelEntity
            get() = ArkLevelEntity(sha = "", width = 0, height = 0)
    }
}
