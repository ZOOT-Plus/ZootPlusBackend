import org.hidetake.gradle.swagger.generator.GenerateSwaggerCode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    java

    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.spring)

    alias(libs.plugins.spring)
    alias(libs.plugins.spring.deps)
    alias(libs.plugins.openapi)
    alias(libs.plugins.swagger.generator)
    alias(libs.plugins.git.properties)
    alias(libs.plugins.ktlint)
}

group = "plus.zoot"
version = "2.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
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
    maven(url = "https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    kapt(libs.spring.processor)

    testImplementation(libs.spring.test)
    testImplementation(libs.mockk)

    implementation(libs.spring.web)
    implementation(libs.spring.webflux)
    implementation(libs.spring.security)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.data.redis)
    implementation(libs.spring.data.mongodb)
    implementation(libs.spring.validation)
    implementation(libs.spring.cache)

    implementation(libs.springdoc.openapi)
    implementation(libs.therapi)
    kapt(libs.therapi.processor)

    // Kotlin
    implementation(kotlin("reflect"))
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.coroutines.reactor)

    // Kotlin-Logging
    implementation(libs.kotlin.logging)

    // Ktorm and JDBC with PG
    implementation(libs.ktorm)
    implementation(libs.ktorm.jackson)
    implementation(libs.ktorm.postgresql)
    runtimeOnly(libs.postgresql)

    // Hutool
    implementation(libs.javax.mail)
    implementation(libs.hutool.extra)
    implementation(libs.hutool.jwt)
    implementation(libs.hutool.dfa)

    // MapStruct
    implementation(libs.mapstruct)
    kapt(libs.mapstruct.processor)

    implementation(libs.ik.analyzer)

    // Git
    implementation(libs.jgit)
    implementation(libs.jgit.ssh.apache.agent)
    implementation(libs.freemarker)

    // Utilities
    implementation(libs.caffeine)
    implementation(libs.json.schema) {
        exclude("commons-logging")
    }
    implementation(libs.guava)

    implementation(libs.jackson.datatype.jsr310)

    swaggerCodegen(libs.swagger.generator.cli)

    implementation(libs.pinyin4j)
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
        jvmArgs = listOf("-XX:+EnableDynamicAgentLoading")
    }
}

gitProperties {
    failOnNoGitDirectory = false
    keys = listOf("git.branch", "git.commit.id", "git.commit.id.abbrev", "git.commit.time")
}

ktlint {
    ignoreFailures = false
    version = "1.5.0"

    reporters {
        reporter(ReporterType.PLAIN)
    }
}
