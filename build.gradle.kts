import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
    id("org.openapi.generator") version "7.22.0"
    id("com.gorylenko.gradle-git-properties") version "3.0.2"
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"

    val ktVersion = "2.3.21"
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
    maven(url = "https://maven.aliyun.com/repository/public")
    maven(url = "https://maven.aliyun.com/repository/spring")
    maven(url = "https://maven.aliyun.com/repository/spring-plugin")
    maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
    mavenCentral()
}

dependencies {
    val ktormVersion = "4.1.1"
    val hutoolVersion = "5.8.43"
    val mapstructVersion = "1.6.3"

    kapt("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("io.mockk:mockk:1.14.4")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    implementation("org.aspectj:aspectjrt:1.9.25.1")
    implementation("org.springframework:spring-aspects")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-scalar:3.0.3")
    implementation("com.github.therapi:therapi-runtime-javadoc:0.15.0")
    kapt("com.github.therapi:therapi-runtime-javadoc-scribe:0.15.0")

    // kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    // kotlin-logging
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.03")

    // ktorm connect with spring-jdbc
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.ktorm:ktorm-core:$ktormVersion")
    implementation("org.ktorm:ktorm-support-postgresql:$ktormVersion")
    implementation("org.postgresql:postgresql:42.7.7")
    // hutool 的邮箱工具类依赖
    implementation("com.sun.mail:javax.mail:1.6.2")
    implementation("cn.hutool:hutool-extra:$hutoolVersion")
    implementation("cn.hutool:hutool-jwt:$hutoolVersion")
    implementation("cn.hutool:hutool-dfa:$hutoolVersion")

    // mapstruct
    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    kapt("org.mapstruct:mapstruct-processor:$mapstructVersion")

    implementation("com.github.magese:ik-analyzer:8.5.0")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache.agent:7.1.0.202411261347-r")
    implementation("org.freemarker:freemarker:2.3.34")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")
    implementation("com.networknt:json-schema-validator:1.5.8")

    implementation("com.belerweb:pinyin4j:2.5.0")
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
fun TaskContainer.registerOpenApiGen(
    name: String,
    language: String,
    configFilePath: String,
    outputSubDir: String,
) = register<GenerateTask>("generateSwaggerCode$name") {
    group = "swagger"
    description = "Generate $name client code from OpenAPI spec"

    dependsOn("generateOpenApiDocs")

    generatorName.set(language)
    inputSpec.set(swaggerInputFile.asFile.absolutePath)
    outputDir.set(clientDir.map { it.dir(outputSubDir) }.get().asFile.absolutePath)
    configFile.set(file(configFilePath))
}

// The typescript-fetch generator sometimes produces model .ts files but
// omits them from the barrel models/index.ts.  This task regenerates the
// barrel so every model file is exported.
val fixTsBarrelExport by tasks.registering {
    group = "swagger"
    description = "Regenerate models/index.ts to export every model file"
    dependsOn("generateSwaggerCodeTsFetch")

    doLast {
        val modelsDir = clientDir.get().dir("ts-fetch-client/src/models").asFile
        val exports = modelsDir.listFiles()
            ?.filter { it.extension == "ts" && it.nameWithoutExtension != "index" }
            ?.sortedBy { it.nameWithoutExtension }
            ?.joinToString("\n") { "export * from './${it.nameWithoutExtension}';" }
            ?: return@doLast

        val indexFile = modelsDir.toPath().resolve("index.ts").toFile()
        val newContent = """/* tslint:disable */
/* eslint-disable */
$exports
"""
        if (indexFile.readText() != newContent) {
            indexFile.writeText(newContent)
            println("  Regenerated models/index.ts with ${exports.lines().count()} exports")
        }
    }
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
            fixTsBarrelExport,
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
