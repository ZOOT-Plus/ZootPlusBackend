# ZootPlusBackendCenter

使用 Kotlin 编写的 ZOOT Plus 服务器后端

## 开发技术栈

- kotlin 2.4 (Java 25)
- SpringBoot 4
  - spring-security
  - springdoc-openapi
- PostgreSQL
- Valkey (或Redis)

## 本地开发指南

1. 你需要一个有 Valkey (或Redis) 和 PostgreSQL 的环境，如果你是windows用户，可以从 [redis-windows](https://github.com/redis-windows/redis-windows/releases) 中下载Redis使用。 您也可以直接使用 [](./dev-docker/docker-compose.yml) 来启动 docker 服务
2. 无需手动初始化数据库：应用首次启动时 Flyway 会根据 `src/main/resources/db/migration` 自动建表
3. 使用你喜欢的 IDE 导入此项目，复制 [](/src/main/resources/application-template.yml) 到同目录下，命名为 `application-dev.yml`，修改数据库配置以符合你自己配置的环境。
4. 下载安装 JDK 25 或者以上版本的 JDK， 可以考虑从 [zuluJDK](https://www.azul.com/downloads/?version=java-25-lts&package=jdk) 或者 [libreicaJDK](https://bell-sw.com/pages/downloads/#jdk-25-lts) 下载安装。 Jetbrains Idea 可以使用自带的 JDK 管理器进行下载
5. 运行 `./gradlew bootRun`, windows 环境为 `./gradlew.bat bootRun`
6. 首次运行建议修改配置文件中的 `maa-copilot.task-cron.ark-level` 配置，这样可以将明日方舟中的关卡数据同步到你本地的
   数据库中，为了防止反复调用造成调试的麻烦，建议首次运行同步成功后再将配置修改回去
7. 本项目使用 [ScalaR](https://github.com/ScalaR/ScalaR) 作为 OpenAPI 展示工具，本地启动时可通过 http://127.0.0.1:8848/scalar 调试

## 数据库迁移（Flyway）

本项目使用 [Flyway](https://flywaydb.org/) 管理数据库 Schema，迁移脚本位于 `src/main/resources/db/migration/`，首次接入由 `V1__init.sql` 建表。Spring Boot 会在应用启动时自动执行迁移（`spring.flyway.enabled: true`）。

### 已有库表的老项目接入

如果你的数据库已经建好全部表（例如旧版本中使用过已移除的 `docker/init.sql`），**首次启动接入 Flyway 前必须先建立 baseline**，否则 Flyway 会在「非空 schema 且无 `flyway_schema_history` 表」时直接报错，导致应用启动失败。

操作方法：在 `application.yml`（或你的 `application-prod.yml` / `application-dev.yml`）中打开以下两项注释：

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1   # V1__init.sql 视为已应用并跳过，不会重建/破坏现有表
```

首次启动后 Flyway 会创建 `flyway_schema_history` 并写入 baseline（版本 1），`V1__init.sql` 因版本号 ≤ baseline 被跳过，后续 `V2__...` 迁移才会真正执行。baseline 成功后即可把这两项重新注释掉。

> 注意：baseline 跳过的 `V1__init.sql` 并不保证与线上实际结构完全一致（存在个别默认值差异），后续新增迁移若依赖「V1 = 当前线上结构」这一假设时请另行核对。

全新空库无需此操作，`V1__init.sql` 会正常建表。

## 项目结构

- config # 存放 spring 配置
- common # 共享的逻辑
- controller # 交互层
  - request # 入参类型
  - response # 响应类型
- repository # 数据仓库层，用于和数据库交互
  - entity # 与数据库字段对应的类型
- service # 业务处理层，复杂或者公用逻辑放在这里
  - model # 应用内传输用类型放这里

## 编译与部署

1. 安装 JDK 25，可以考虑从 [zuluJDK](https://www.azul.com/downloads/?version=java-25-lts&package=jdk) 或者 [libreicaJDK](https://bell-sw.com/pages/downloads/#jdk-25-lts) 下载安装
2. clone 此项目 `git clone https://github.com/ZOOT-Plus/ZootPlusBackend.git`
3. 进入此项目目录 `cd ZootPlusBackend`
4. 编译项目 `./gradlew bootJar`，windows 环境下请使用 `gradlew.bat bootJar`
5. 获得编译后的 jar 文件 `cp ./build/libs/ZootPlusBackend-2.0.jar .`
6. 复制一份配置文件 `cp ./build/resources/main/application-template.yml ./application-prod.yml`
7. 修改配置文件 `application-prod.yml`
8. 运行项目 `java -jar ZootPlusBackend-2.0.jar --spring.profiles.active=prod`

## Join us!

QQ Group: 724540644
