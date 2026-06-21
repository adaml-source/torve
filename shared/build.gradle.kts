import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.sqldelight)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        FileInputStream(file).use(::load)
    }
}

fun readTmdbApiKey(): String {
    return providers.gradleProperty("TMDB_API_KEY").orNull
        ?: System.getenv("TMDB_API_KEY")
        ?: localProperties.getProperty("TMDB_API_KEY")
        ?: ""
}

fun escapeForBuildConfig(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

val generatedAndroidTmdbApiKeyDir = layout.buildDirectory.dir("generated/torve/tmdb/androidMain/kotlin")
val generateAndroidTmdbApiKey by tasks.registering {
    val apiKey = providers.provider { readTmdbApiKey() }
    inputs.property("tmdbApiKey", apiKey)
    outputs.dir(generatedAndroidTmdbApiKeyDir)
    doLast {
        val file = generatedAndroidTmdbApiKeyDir.get()
            .file("com/torve/data/metadata/TmdbGeneratedApiKey.android.kt")
            .asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.torve.data.metadata

            internal const val ANDROID_TMDB_API_KEY: String = "${escapeForBuildConfig(readTmdbApiKey())}"
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.FlowPreview")
    }

    android {
        namespace = "com.torve.shared"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Ktor
            implementation(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.logging)

            // SQLDelight
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            // KotlinX
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Koin
            implementation(libs.koin.core)
        }

        androidMain {
            kotlin.srcDir(generateAndroidTmdbApiKey)
            dependencies {
                implementation(libs.ktor.okhttp)
                implementation(libs.sqldelight.android)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.qrcode.kotlin)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.cio)
                implementation(libs.sqldelight.sqlite)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.qrcode.kotlin)
            }
        }

        iosMain.dependencies {
            implementation(libs.ktor.darwin)
            implementation(libs.sqldelight.native)
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.mock)
        }
    }
}

sqldelight {
    databases {
        create("TorveDatabase") {
            packageName.set("com.torve.db")
        }
    }
}

tasks.register("checkEpgStreamingSafety") {
    group = "verification"
    description = "Fails when EPG fetch path uses full-buffer or bridge APIs that can trigger OOM."
    doLast {
        val targets = listOf(
            "shared/src/commonMain/kotlin/com/streamvault/data/channels/ChannelRepositoryImpl.kt",
            "shared/src/androidMain/kotlin/com/streamvault/data/channels/GzipSupport.android.kt",
            "shared/src/androidMain/kotlin/com/streamvault/data/channels/EpgParserDb.android.kt",
        )
        val forbidden = listOf(
            "toInputStream(",
            "body<ByteArray>",
            "body<String>",
            "readBytes(",
            "readText(",
            "ByteArrayOutputStream",
        )
        val violations = mutableListOf<String>()
        targets.forEach { relativePath ->
            val file = rootProject.file(relativePath)
            if (!file.exists()) return@forEach
            val text = file.readText()
            forbidden.forEach { token ->
                if (text.contains(token)) {
                    violations += "$relativePath -> $token"
                }
            }
        }
        if (violations.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Forbidden buffering calls detected in EPG path:")
                    violations.forEach { appendLine("- $it") }
                },
            )
        }
    }
}
