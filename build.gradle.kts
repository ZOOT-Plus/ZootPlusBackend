import org.hidetake.gradle.swagger.generator.GenerateSwaggerCode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
    id("org.hidetake.swagger.generator") version "2.19.2"
    id("com.gorylenko.gradle-git-properties") version "2.5.2"

    val ktVersion = "2.2.0"
    kotlin("jvm") version ktVersion
    kotlin("plugin.spring") version ktVersion
    kotlin("kapt") version ktVersion

    id("org.jlleitschuh.gradle.ktlint") version "13.0.0"
}

group = "plus.zoot"
version = "2.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
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
    val hutoolVersion = "5.8.39"
    val mapstructVersion = "1.6.3"

    kapt("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("io.mockk:mockk:1.14.4")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
    implementation("com.github.therapi:therapi-runtime-javadoc:0.15.0")
    kapt("com.github.therapi:therapi-runtime-javadoc-scribe:0.15.0")

    // kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    // kotlin-logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")

    // ktorm
    implementation("org.ktorm:ktorm-core:$ktormVersion")
    implementation("org.ktorm:ktorm-jackson:${ktormVersion}")
    implementation("org.ktorm:ktorm-support-postgresql:${ktormVersion}")
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

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    swaggerCodegen("org.openapitools:openapi-generator-cli:7.14.0")

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

swaggerSources {
    val clientDir = layout.buildDirectory.dir("clients").get()
    val swaggerOutputFile = swaggerOutputDir.get().file(swaggerOutputName).asFile
    create("TsFetch") {
        setInputFile(swaggerOutputFile)
        code(
            closureOf<GenerateSwaggerCode> {
                language = "typescript-fetch"
                configFile = file("client-config/ts-fetch.json")
//            templateDir = file('client-config/typescript-fetch')
                rawOptions = listOf("-e", "mustache")
                outputDir = file(clientDir.dir("ts-fetch-client"))
            },
        )
    }
    create("CSharp") {
        setInputFile(swaggerOutputFile)
        code(
            closureOf<GenerateSwaggerCode> {
                language = "csharp"
                configFile = file("client-config/csharp-netcore.json")
                outputDir = file(clientDir.dir("csharp-client"))
//            rawOptions = listOf("--type-mappings", "binary=System.IO.Stream")
            },
        )
    }
    create("Cpp") {
        setInputFile(swaggerOutputFile)
        code(
            closureOf<GenerateSwaggerCode> {
                language = "cpp-restsdk"
                configFile = file("client-config/cpp.json")
                outputDir = file(clientDir.dir("cpp-client"))
            },
        )
    }
    create("Rust") {
        setInputFile(swaggerOutputFile)
        code(
            closureOf<GenerateSwaggerCode> {
                language = "rust"
                configFile = file("client-config/rust.json")
                outputDir = file(clientDir.dir("rust-client"))
            },
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
