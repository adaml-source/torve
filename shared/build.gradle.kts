import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
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

kotlin {
    jvmToolchain(17)

    androidTarget {
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

            // QR code rendering — used by credential-transfer receive UIs
            // on every platform. Pure-Kotlin KMP library; outputs raw PNG
            // bytes that each platform converts to its own bitmap type.
            implementation(libs.qrcode.kotlin)
        }

        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.sqldelight.android)
            implementation(libs.kotlinx.coroutines.android)
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.cio)
                implementation(libs.sqldelight.sqlite)
                implementation(libs.kotlinx.coroutines.swing)
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

android {
    namespace = "com.torve.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "TMDB_API_KEY", "\"${escapeForBuildConfig(readTmdbApiKey())}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
