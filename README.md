# ZootPlusBackendCenter

使用 Kotlin 编写的 ZOOT Plus 服务器后端

## 开发技术栈

- kotlin 2.2 (Java 21)
- SpringBoot 3
  - spring-security
  - springdoc-openapi
- PostgreSQL
- Valkey (或Redis)

## 本地开发指南

1. 你需要一个有 Valkey (或Redis) 和 PostgreSQL 的环境，如果你是windows用户，可以从 https://github.com/redis-windows/redis-windows/releases 中下载Redis使用。 您也可以直接使用 `./dev-docker/docker-compose.yml` 来启动 docker 服务
2. 使用你喜欢的 IDE 导入此项目，复制 `/src/main/resources/application-template.yml` 到同目录下，命名为 `application-dev.yml`，修改数据库配置以符合你自己配置的环境。
3. 下载安装 JDK 21 或者以上版本的 JDK， 可以考虑从 [zuluJDK](https://www.azul.com/downloads/?version=java-21-lts&package=jdk) 或者 [libreicaJDK](https://bell-sw.com/pages/downloads/#jdk-21-lts) 下载安装。 Jetbrains Idea 可以使用自带的 JDK 管理器进行下载
4. 运行 `./gradlew bootRun`, windows 环境为 `./gradlew.bat bootRun`
5. 首次运行建议修改配置文件中的 `maa-copilot.task-cron.ark-level` 配置，这样可以将明日方舟中的关卡数据同步到你本地的
   数据库中，为了防止反复调用造成调试的麻烦，建议首次运行同步成功后再将配置修改回去

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

1. 安装 JDK 21，可以考虑从 [zuluJDK](https://www.azul.com/downloads/?version=java-17-lts&package=jdk)
   或者 [libreicaJDK](https://bell-sw.com/pages/downloads/#/java-17-lts) 下载
2. clone 此项目 `git clone https://github.com/ZOOT-Plus/ZootPlusBackend.git`
3. 进入此项目目录 `cd ZootPlusBackend`
4. 编译项目 `./gradlew bootJar`，windows 环境下请使用 `gradlew.bat bootJar`
5. 获得编译后的 jar 文件 `cp ./build/libs/ZootPlusBackend-2.0.jar .`
6. 复制一份配置文件 `cp ./build/resources/main/application-template.yml ./application-prod.yml`
7. 修改配置文件 `application-prod.yml`
8. 运行项目 `java -jar ZootPlusBackend-2.0.jar --spring.profiles.active=prod`

## Join us!

QQ Group: 724540644
