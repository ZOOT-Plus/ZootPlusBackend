# ZootPlusBackend

## 项目概览

- 技术栈：Spring Boot 4 / Kotlin 2.4 / Java 25 / Gradle 9 / PostgreSQL 18
- 持久层：Jdbi 3（SQL Freemarker 模板 + SqlObject DAO）；测试用 zonky embedded-postgres
- Schema：Flyway 11（`src/main/resources/db/migration/`），只追加不修改已发布版本
- 改动后必须跑 `./gradlew ktlintFormat`

## 数据库访问

### 实体映射

- 实体为 Kotlin `data class`，KotlinMapper 默认 snake_case aware（`user_id` ↔ `userId`）
- 枚举（`CopilotType`/`CopilotSetStatus`/`CommentStatus`/`RatingType`/`SiteMessageType`）：PG text 列存枚举 `name`，Jdbi 默认按 name 绑定/映射，无需自定义工厂
- jsonb 列（`copilot_set.copilot_ids: List<Long>`）：须自定义 `ColumnMapperFactory` + `ArgumentFactory`（见 `CopilotSetRepository.kt` 的 `CopilotIdsColumnMapperFactory`）。坑：Kotlin `List<Long>` 反射出精确与协变两种 Java 形态，只匹配一种会致 PG 数组解码抛 `ArrayIndexOutOfBoundsException`
- `Instant` 列（如 `user.pwd_update_time` 为 timestamp(3)）须列映射器

### SqlObject

- 插入：`@SqlUpdate` + `@BindKotlin` 整对象绑定 + `@GetGeneratedKeys("id")`；自增 id 不在 INSERT 列清单时方法须加 `@AllowUnusedBindings`
- `jdbi.onDemand(XxxDao::class.java)` 每次方法调用独立开/关 handle：`jdbi.useTransaction` 事务内调用 onDemand DAO 方法**不会**加入该事务；需要原子性时须在同一 handle 上执行（`handle.attach(XxxDao::class.java)` 或直接用该 handle 的语句对象）
- `jdbi.withHandle<R, X>`：`X` 仅出现在 throws 子句时 Kotlin 无法推断类型参数，须显式写全两个类型参数（如 `jdbi.withHandle<Long, Exception> { ... }`）
- `@Define` 只提供模板变量不产生绑定：freemarker `<#if xxx??>` 判断用 `@Define`，SQL 里 `:xxx` 绑定须同一参数叠加 `@Bind("xxx")`；条件不成立时绑定不被引用，靠 `@AllowUnusedBindings` 容忍
- 动态条件 SQL 用 `@UseFreemarkerEngine` + `<#if>`，仅标注在需要的方法上（全局启用会使普通 SQL 的 `<` 与 FTL 冲突）

### SQL 实践

- 分页查询必须带 `ORDER BY`
- PG 保留字 `"delete"`、`"user"` 须加双引号
- 空集合 `IN ()`：DAO 层防御性返回空列表
- jsonb 包含查询：`col @> :jsonText::jsonb`，绑定 JSON 数组文本

## 测试

- 基类 `TestDbSupport.kt`：JVM 级单例 embedded PG（多类共享），Flyway 建表（与生产共用 `db/migration`），`@BeforeEach` TRUNCATE 全部业务表 `RESTART IDENTITY CASCADE`
- 不依赖 Spring 上下文（无 `@SpringBootTest`），repository 测试直接 `XxxRepository(jdbi)` 构造
- mockk mock 服务依赖（如 `ArkLevelService`），DB 用真实库
- 测试库选 zonky embedded-postgres（纯 JVM 嵌入式 PG）而非 testcontainers：开发机为 WSL 无 docker

## Flyway

- 迁移文件 `V{n}__描述.sql`，只追加不修改已发布版本，优先幂等安全，有问题先反问开发者
- 生产由 Spring Boot 自动配置执行（`spring.flyway.enabled: true`）；测试在 `TestDbSupport` 手动 `migrate()`

## 遗留事项（基线行为，改动需谨慎）

- `updateEntity` 两派语义：rating / copilot 用脏检查快照复刻 Ktorm `flushChanges`（读回时存快照，updateEntity 与快照 diff 只 SET 变化列；无快照时退化全列 SET），其余（comments_area / copilot_set / site_message / user）全列 SET；ark_level 已移除 `updateEntity`（无 production 调用方），save/saveAll 统一走 INSERT ... ON CONFLICT (id) DO UPDATE 全列 upsert
- `save()` 显式指定不存在的 id 时按给定值插入且不推进自增序列（Ktorm 基线）
- LIKE 通配符不转义：`ArkLevelRepository.findByLevelIdFuzzy` / `CopilotSetService.query` 的 keyword 的 `%`、`_` 按 PG LIKE 通配符解释（基线行为，非 SQL 注入风险，参数化绑定）；`UserService.search` 已转义 `%`/`_`，搜索词按字面匹配（LIKE ... ESCAPE `\\`）
- 事务：jdbi3-spring 依赖在 classpath 但 `SpringTransactionPlugin` **未安装**（`@Transactional` 不覆盖 Jdbi），repository 内事务由 `jdbi.useTransaction` 自管
