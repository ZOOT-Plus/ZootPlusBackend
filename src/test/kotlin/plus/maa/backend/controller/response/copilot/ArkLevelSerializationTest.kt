package plus.maa.backend.controller.response.copilot

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.config.SerializationConfig
import plus.maa.backend.controller.response.MaaResult

/**
 * `/arknights/level/v2` 响应体的序列化行为。
 *
 * 锁死两件事，它们都是「静默退化」型缺陷（改了 Json 配置或 DTO 默认值就中招，功能测试未必发现）：
 * 1. `withSize=false` 时 `width`/`height` 必须**整个省略键**而非 `"width": null` —— 后者会让 no-size
 *    变体凭空多出字节，正是这个变体要省的东西；
 * 2. 字段名必须是 snake_case（对齐 v1 的线上契约，前端与 4 套生成 SDK 都按此消费）。
 */
class ArkLevelSerializationTest {

    /** 与生产同一份配置（SerializationConfig.kotlinJson 的 Json 实例）。 */
    private val json = SerializationConfig().kotlinJson()

    private fun body(width: Int? = null, height: Int? = null) = MaaResult.success(
        LevelPayload(
            version = "0dffa3500000000000000000000000ab",
            levels = listOf(
                ArkLevelInfoV2(
                    levelId = "activities/act53side/level_act53side_sub-1-2",
                    stageId = "act53side_s02",
                    catOne = "活动关卡",
                    catTwo = "直到大地变成一颗酸橙",
                    catThree = "TO-S-2",
                    name = "通行新选择",
                    width = width,
                    height = height,
                ),
            ),
        ),
    )

    @Test
    fun noSizeOmitsWidthAndHeightKeysEntirely() {
        val text = json.encodeToString(body())

        assertFalse(text.contains("width"), "no-size 变体不应出现 width 键，实际：$text")
        assertFalse(text.contains("height"), "no-size 变体不应出现 height 键，实际：$text")
        assertFalse(text.contains("null"), "null 不应以任何形式出现（MaaResult.message 亦然），实际：$text")
    }

    @Test
    fun withSizeIncludesWidthAndHeight() {
        val text = json.encodeToString(body(width = 12, height = 8))

        assertTrue(text.contains("\"width\":12"), "实际：$text")
        assertTrue(text.contains("\"height\":8"), "实际：$text")
    }

    @Test
    fun fieldNamesFollowV1SnakeCaseContract() {
        val text = json.encodeToString(body(width = 12, height = 8))

        assertTrue(text.contains("\"status_code\":200"), "实际：$text")
        assertTrue(text.contains("\"data\":{"))
        assertTrue(text.contains("\"version\":"))
        assertTrue(text.contains("\"levels\":["))
        assertTrue(text.contains("\"level_id\":"))
        assertTrue(text.contains("\"stage_id\":"))
        assertTrue(text.contains("\"cat_one\":"))
        assertTrue(text.contains("\"cat_two\":"))
        assertTrue(text.contains("\"cat_three\":"))
    }

    @Test
    fun versionProbeBodyShape() {
        // 探测端点两个键都必须在（缺一个客户端就没法判断该变体是否更新），且同样 snake_case
        val text = json.encodeToString(
            MaaResult.success(LevelVersions(full = "a".repeat(32), lite = "b".repeat(32))),
        )

        assertEquals("""{"status_code":200,"data":{"full":"${"a".repeat(32)}","lite":"${"b".repeat(32)}"}}""", text)
    }

    @Test
    fun omitNullsIsGlobalDefaultJsonBehavior() {
        // 说明「省略键」不是 LevelPayload 的局部行为，而是全局 Json 配置的效果：
        // 若将来把 explicitNulls 改回 true，上面几条断言会先失败，指向这里
        val text = defaultJson.encodeToString(
            ArkLevelInfoV2(levelId = "a", stageId = "b", catOne = "c", catTwo = "d", catThree = "e", name = "f"),
        )

        assertFalse(text.contains("width"))
        assertFalse(text.contains("null"))
    }
}
