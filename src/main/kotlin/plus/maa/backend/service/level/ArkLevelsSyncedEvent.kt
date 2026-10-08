package plus.maa.backend.service.level

/**
 * `ark_level` 发生了可能改变 `/arknights/level/v2` 响应内容的写入（地图同步落库、活动名回填、
 * `updated_at` 存量回填）。
 *
 * 发布方：[ArkLevelService] 的写路径（实际写过数据才发）；消费方：
 * [ArkLevelV2Service.onArkLevelsChanged] 失效并预热 v2 快照缓存。
 *
 * 设计意图是**解耦**：写路径不知道读路径的缓存结构，只声明「数据变了」这一事实；快照缓存因此可以
 * 采用远长于同步周期的 TTL，由事件保证「更新后立即可见」。新增会改变 v2 响应内容的写路径时，
 * 完成后同样要发布本事件，否则新数据要等缓存 TTL 自然过期才可见。
 *
 * 开放状态批处理通过 [ArkLevelOpenStatusChangedEvent] 通知推荐缓存；is_open/close_time
 * 不进 v2 响应（见 `ArkLevelV2Service.digest`），沿用独立事件。
 */
data object ArkLevelsSyncedEvent
