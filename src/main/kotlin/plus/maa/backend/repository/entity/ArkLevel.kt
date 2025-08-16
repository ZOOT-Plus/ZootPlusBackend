package plus.maa.backend.repository.entity

import java.time.LocalDateTime

/**
 * 地图数据
 *
 * @author john180
 */
data class ArkLevel(
    val id: String? = null,
    val levelId: String? = null,
    val stageId: String? = null,
    // 文件版本, 用于判断是否需要更新
    val sha: String = "",
    // 地图类型, 例: 主线、活动、危机合约
    var catOne: String? = null,
    // 所属章节, 例: 怒号光明、寻昼行动
    var catTwo: String? = null,
    // 地图ID, 例: 7-18、FC-1
    var catThree: String? = null,
    // 地图名, 例: 冬逝、爱国者之死
    val name: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    // 只是服务器认为的当前版本地图是否开放
    var isOpen: Boolean? = null,
    // 非实际意义上的活动地图关闭时间，只是服务器认为的关闭时间
    var closeTime: LocalDateTime? = null,
) {
    companion object {
        // 暂时这么做，有机会用和类型分别处理成功、失败以及解析器未实现的情况
        val EMPTY: ArkLevel = ArkLevel()
    }
}
