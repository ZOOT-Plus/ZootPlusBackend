import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.springframework.boot") version "4.0.7"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
    id("org.openapi.generator") version "7.23.0"
    id("com.gorylenko.gradle-git-properties") version "3.0.2"
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"

    val ktVersion = "2.4.10"
    kotlin("jvm") version ktVersion
    kotlin("plugin.spring") version ktVersion
    kotlin("plugin.serialization") version ktVersion
    kotlin("kapt") version ktVersion

    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "plus.zoot"
version = "2.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_25
    }
}

kapt {
    keepJavacAnnotationProcessors = true
}

repositories {
    if (System.getenv("CI") != "true") {
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/spring")
        maven(url = "https://maven.aliyun.com/repository/spring-plugin")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
    }
    mavenCentral()
}

dependencies {
    val hutoolVersion = "5.8.47"
    val mapstructVersion = "1.6.3"

    kapt("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 平台 binary 按当前构建机自动选择（zonkyBinaryArtifact，见文件底部函数），不固定平台：
    // 默认传递依赖会引入全部平台 jar，这里排除后只引入当前系统对应的一个
    testImplementation("io.zonky.test:embedded-postgres:2.2.2") {
        exclude(group = "io.zonky.test.postgres")
    }
    testImplementation(zonkyBinaryArtifact())

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("org.aspectj:aspectjrt:1.9.25.1")
    implementation("org.springframework:spring-aspects")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-scalar:3.0.3")
    // swagger-core uses Jackson 2.x; without its kotlin-module, all Kotlin properties default to nullable
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")
    implementation("com.github.therapi:therapi-runtime-javadoc:0.15.0")
    kapt("com.github.therapi:therapi-runtime-javadoc-scribe:0.15.0")

    // kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    // kotlin-logging
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(platform("org.jdbi:jdbi3-bom:3.54.0"))
    implementation("org.jdbi:jdbi3-core")
    implementation("org.jdbi:jdbi3-sqlobject")
    implementation("org.jdbi:jdbi3-kotlin")
    implementation("org.jdbi:jdbi3-kotlin-sqlobject")
    implementation("org.jdbi:jdbi3-postgres")
    implementation("org.jdbi:jdbi3-spring")
    // 动态 SQL 解析结果由 jdbi3-caffeine-cache 缓存
    implementation("org.jdbi:jdbi3-freemarker")
    implementation("org.jdbi:jdbi3-caffeine-cache")
    implementation("org.postgresql:postgresql:42.7.13")
    // hutool 的邮箱工具类依赖
    implementation("com.sun.mail:javax.mail:1.6.2")
    implementation("cn.hutool:hutool-extra:$hutoolVersion")
    implementation("cn.hutool:hutool-jwt:$hutoolVersion")
    implementation("cn.hutool:hutool-dfa:$hutoolVersion")

    // mapstruct
    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    kapt("org.mapstruct:mapstruct-processor:$mapstructVersion")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache.agent:7.7.0.202606012155-r")
    implementation("org.freemarker:freemarker:2.3.34")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")
    implementation("com.networknt:json-schema-validator:1.5.8")

    implementation("com.belerweb:pinyin4j:2.5.1")
    testImplementation(kotlin("test"))
}

val swaggerOutputDir = layout.buildDirectory.dir("docs")
val swaggerOutputName = "swagger.json"

openApi {
    apiDocsUrl = "http://localhost:8848/v3/api-docs"
    outputDir = swaggerOutputDir
    outputFileName = swaggerOutputName
    waitTimeInSeconds = 30
}

val swaggerInputFile = swaggerOutputDir.get().file(swaggerOutputName)
val clientDir = layout.buildDirectory.dir("clients")

// Helper: register an OpenAPI code-gen task using the official plugin's GenerateTask
fun TaskContainer.registerOpenApiGen(name: String, language: String, configFilePath: String, outputSubDir: String) =
    register<GenerateTask>("generateSwaggerCode$name") {
        group = "swagger"
        description = "Generate $name client code from OpenAPI spec"

        dependsOn("generateOpenApiDocs")

        generatorName.set(language)
        inputSpec.set(swaggerInputFile.asFile.absolutePath)
        outputDir.set(clientDir.map { it.dir(outputSubDir) }.get().asFile.absolutePath)
        configFile.set(file(configFilePath))
    }

tasks {
    registerOpenApiGen("TsFetch", "typescript-fetch", "client-config/ts-fetch.json", "ts-fetch-client")
    registerOpenApiGen("CSharp", "csharp", "client-config/csharp-netcore.json", "csharp-client")
    registerOpenApiGen("Cpp", "cpp-restsdk", "client-config/cpp.json", "cpp-client")
    registerOpenApiGen("Rust", "rust", "client-config/rust.json", "rust-client")

    register("generateSwaggerCode") {
        group = "swagger"
        description = "Generate all client code from OpenAPI spec"
        dependsOn(
            "generateSwaggerCodeTsFetch",
            "generateSwaggerCodeCSharp",
            "generateSwaggerCodeCpp",
            "generateSwaggerCodeRust",
        )
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}

gitProperties {
    failOnNoGitDirectory = false
    keys = listOf("git.branch", "git.commit.id", "git.commit.id.abbrev", "git.commit.time")
}

ktlint {
    ignoreFailures = false

    reporters {
        reporter(ReporterType.PLAIN)
    }
}

/**
 * 使用构建平台的pg二进制
 *
 * zonky 的二进制命名规律：`embedded-postgres-binaries-os-arch`
 * os ∈ {linux, darwin, windows}，arch ∈ {amd64, arm64v8, i386, ppc64le}
 */
fun zonkyBinaryArtifact(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val platform = when {
        os.contains("linux") && (arch.contains("amd64") || arch.contains("x86_64")) -> "linux-amd64"
        os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) -> "linux-arm64v8"
        os.contains("linux") && arch.contains("86") -> "linux-i386"
        os.contains("linux") && arch.contains("ppc64") -> "linux-ppc64le"
        os.contains("mac") && (arch.contains("amd64") || arch.contains("x86_64")) -> "darwin-amd64"
        os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "darwin-arm64v8"
        os.contains("win") && (arch.contains("amd64") || arch.contains("x86_64")) -> "windows-amd64"
        os.contains("win") && arch.contains("86") -> "windows-i386"
        else -> error("不支持的平台（zonky embedded-postgres）：os.name=${System.getProperty("os.name")}, os.arch=${System.getProperty("os.arch")}")
    }
    return "io.zonky.test.postgres:embedded-postgres-binaries-$platform:18.4.0"
}
