package plus.maa.backend.common.extensions

/**
 * 通用 Kotlin 扩展。
 *
 * 注意：此处曾有 `lazySuspend`（永久缓存挂起块结果的辅助函数），它是「活动关卡缺活动名」缺陷的
 * 直接成因——`ArkLevelService` 曾用它缓存游戏数据快照，导致快照在整个 JVM 生命周期内永不更新。
 * 该服务现已改为按需刷新的可变快照（见 `ArkLevelService.dataHolder`），最后一个调用方随之消失。
 * 若将来确实需要「只计算一次」的挂起缓存，请自行实现并明确失效策略。
 */
inline fun <T> T?.requireNotNull(lazyMessage: () -> Any): T = requireNotNull(this, lazyMessage)

fun meetAll(vararg cond: Boolean) = cond.all { it }
