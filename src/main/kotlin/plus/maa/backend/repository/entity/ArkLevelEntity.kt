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
) {
    companion object {
        val EMPTY: ArkLevelEntity
            get() = ArkLevelEntity(sha = "", width = 0, height = 0)
    }
}
