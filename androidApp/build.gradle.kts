import java.util.Properties
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

fun String.toBuildConfigStringLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

val torveLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        FileInputStream(file).use(::load)
    }
}

android {
    namespace = "com.torve.android"
    compileSdk = 36
    val pandaBaseUrl = providers.gradleProperty("pandaBaseUrl")
        .orElse(providers.environmentVariable("TORVE_PANDA_BASE_URL"))
        .orElse("https://panda.torve.app")
    val torveDiscordInviteUrl = providers.gradleProperty("torveDiscordInviteUrl")
        .orElse(providers.environmentVariable("TORVE_DISCORD_INVITE_URL"))
        .orElse("https://discord.gg/dVHFAh7Amx")
    val traktClientId = providers.gradleProperty("TRAKT_CLIENT_ID")
        .orElse(providers.environmentVariable("TRAKT_CLIENT_ID"))
        .orElse(torveLocalProperties.getProperty("TRAKT_CLIENT_ID", ""))
    val traktClientSecret = providers.gradleProperty("TRAKT_CLIENT_SECRET")
        .orElse(providers.environmentVariable("TRAKT_CLIENT_SECRET"))
        .orElse(torveLocalProperties.getProperty("TRAKT_CLIENT_SECRET", ""))

    signingConfigs {
        create("release") {
            val props = rootProject.file("keystore.properties")
            if (props.exists()) {
                val keystoreProps = Properties()
                FileInputStream(props).use { keystoreProps.load(it) }
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                // CI / env-var fallback
                storeFile = file(System.getenv("TORVE_KEYSTORE_PATH") ?: "/dev/null")
                storePassword = System.getenv("TORVE_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("TORVE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("TORVE_KEY_PASSWORD") ?: ""
            }
        }
    }

    val baseVersionCode = 118

    defaultConfig {
        applicationId = "com.torve.app"
        minSdk = 24
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "1.2.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-config.pro")
        manifestPlaceholders["torveAllowBackup"] = "false"

        // Default: both ARM ABIs.  Pass -PabiOverride=arm64-v8a for fast dev builds.
        ndk {
            // Applies to native code built by AGP. The vendored mpv/FFmpeg
            // libraries use the separately validated archive below.
            debugSymbolLevel = "FULL"
            val override = providers.gradleProperty("abiOverride").orNull
            if (override != null) {
                abiFilters += override
            } else {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }

        buildConfigField("String", "BUILD_TIMESTAMP", "\"${System.currentTimeMillis()}\"")
        buildConfigField("String", "SYNC_BASE_URL", "\"https://api.torve.app\"")
        buildConfigField("String", "SYNC_WS_URL", "\"wss://api.torve.app/ws\"")
        buildConfigField("String", "PANDA_BASE_URL", "\"${pandaBaseUrl.get()}\"")
        buildConfigField("String", "TORVE_DISCORD_INVITE_URL", torveDiscordInviteUrl.get().toBuildConfigStringLiteral())
        buildConfigField("Boolean", "TORVE_SHOW_DONATION_LINKS", "false")
        buildConfigField("String", "TORVE_DONATION_URL", "\"\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", traktClientId.get().toBuildConfigStringLiteral())
        buildConfigField("String", "TRAKT_CLIENT_SECRET", traktClientSecret.get().toBuildConfigStringLiteral())
    }

    flavorDimensions += listOf("store", "formFactor")
    productFlavors {
        create("google") {
            dimension = "store"
            buildConfigField("Boolean", "HAS_BILLING", "false")
            buildConfigField("Boolean", "SUPPORTS_TV_BILLING", "false")
        }
        create("amazon") {
            dimension = "store"
            // Different applicationId so it can coexist with Google Play version
            applicationIdSuffix = ".amazon"
            buildConfigField("Boolean", "HAS_BILLING", "false")
            // Amazon TV is distributed as a sideloaded APK — no in-app purchase flow.
            // Fire TV uses the same free-access behavior as other store builds.
            buildConfigField("Boolean", "SUPPORTS_TV_BILLING", "false")
        }
        create("mobile") {
            dimension = "formFactor"
            versionCode = 10000 + baseVersionCode
            buildConfigField("Boolean", "HAS_BILLING", "false")
            buildConfigField("Boolean", "SUPPORTS_TV_BILLING", "false")
        }
        create("tv") {
            dimension = "formFactor"
            versionCode = 20000 + baseVersionCode
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Keep instrumentation/dev builds beside the signed app. This avoids
            // signature-conflict installs and, more importantly, prevents a test
            // run from requiring removal of a user's saved production setup.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["torveAllowBackup"] = "false"
            // Keep full metadata for native code built by AGP. Prebuilt
            // mpv/FFmpeg symbols are uploaded using the task below.
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val mpvDebugObjectsZip = providers.gradleProperty("mpvDebugObjectsZip")
    .orElse(providers.environmentVariable("TORVE_MPV_DEBUG_OBJECTS_ZIP"))

val expectedMpvDebugObjectsSha256 =
    "407a4d3c1b1b930d47504716e7d699a3ec79a5585fd5f9dec0401c4f19ed1559"

// SHA-256 values of the stripped delivery binaries from the pinned upstream
// APKs. A native update must update the runtime libraries and this manifest.
val expectedMpvRuntimeSha256 = mapOf(
    "arm64-v8a/libavcodec.so" to "7f073b0611a993d138abcced96b1c9df8befabcac2383ea4e96661e8b5cbfbea",
    "arm64-v8a/libavdevice.so" to "a08ad923f05886d31640ad6236966e71e11dc9d32424912ff18e13b7473e2d39",
    "arm64-v8a/libavfilter.so" to "87e40d632719f7eac632fabf45ebbe6cfc804ca696485181f6aa409abffcbe64",
    "arm64-v8a/libavformat.so" to "45dfbaf01c7eeabc1a3db16940bef76971172dec6cf1a9944fdf6ad68994b40f",
    "arm64-v8a/libavutil.so" to "469b8b65a7f14fbedf08a4f4928cfca0055ab6736af8f994bf4367a1da2f6676",
    "arm64-v8a/libmpv.so" to "e985a6f8ca12c1554ca73bcc1335c657ec1a1d0773ee94448db7f2b8301ebaef",
    "arm64-v8a/libplayer.so" to "83e2a4874c82d96ba66adc6504910f1f042144371d047177f993a105e777cab1",
    "arm64-v8a/libswresample.so" to "e0eb87523d877cb258f17c7a99522134a4fe121a79ea1ebc25ac967e1590781b",
    "arm64-v8a/libswscale.so" to "811ef19dc2d44f54eecb9eefa8b8901396aa06cc220fb159b686a1bf5f9cefb2",
    "armeabi-v7a/libavcodec.so" to "d60cc10596b5b7692e92e8b047296e93e9e73455b1d84175ada2e71b73437e13",
    "armeabi-v7a/libavdevice.so" to "e19c8906b7bff22b70f0c15421e8dbe6a9e82f8fdc334078f12cbc1ee0741ac7",
    "armeabi-v7a/libavfilter.so" to "d272fe2ac837706378f73cac1a3c5f52bbbd067c36e22f291f7de15880046fe6",
    "armeabi-v7a/libavformat.so" to "704df1cc12aa5b1bfa3b441faaf82239adc566b5594d44c3c1f3438340dc1389",
    "armeabi-v7a/libavutil.so" to "5da96d857289758af096eb343972fe3f0aa22391bb253637c409b533ea1ece9f",
    "armeabi-v7a/libmpv.so" to "10ca5468e8538e37eb27c8f564133411f6186819ec3c20daa4fa41f8f6082456",
    "armeabi-v7a/libplayer.so" to "cea6abd22ff90e7a7b5297f474f3e9d0909cb92954190ac852fae402eab8c20e",
    "armeabi-v7a/libswresample.so" to "a176d01ffbec4ac5a2ad76a9dc5e377d289f3cc31a338e154ecbe8c3fbf920f5",
    "armeabi-v7a/libswscale.so" to "6f1258c48a8912fd53faaf2d5fa9ac0de64a127d8c6f45e718aa6087b91b56a4",
)

fun registerNativeSymbolsTask(variantName: String) {
    val capitalizedVariant = variantName.replaceFirstChar { it.uppercaseChar() }
    tasks.register<Zip>("package${capitalizedVariant}NativeSymbols") {
        group = "distribution"
        description = "Validates and packages matching mpv debug symbols for $variantName."

        doFirst {
            val configuredPath = mpvDebugObjectsZip.orNull
                ?: throw GradleException(
                    "Set -PmpvDebugObjectsZip=<path-to-debug-objs.zip> or " +
                        "TORVE_MPV_DEBUG_OBJECTS_ZIP. See docs/third-party-mpv-android.md.",
                )
            val sourceArchive = rootProject.file(configuredPath)
            check(sourceArchive.isFile) { "Native symbol archive does not exist: $sourceArchive" }
            check(sourceArchive.sha256() == expectedMpvDebugObjectsSha256) {
                "Native symbol archive SHA-256 does not match mpv-android 2025-12-27."
            }

            expectedMpvRuntimeSha256.forEach { (relativePath, expectedSha256) ->
                val runtimeLibrary = file("src/main/jniLibs/$relativePath")
                check(runtimeLibrary.isFile) { "Missing runtime library: $runtimeLibrary" }
                check(runtimeLibrary.sha256() == expectedSha256) {
                    "Runtime library no longer matches the pinned symbol set: $relativePath"
                }
            }

            ZipFile(sourceArchive).use { archive ->
                expectedMpvRuntimeSha256.keys.forEach { relativePath ->
                    val symbolEntry = archive.getEntry(relativePath)
                        ?: error("Native symbol archive is missing $relativePath")
                    val runtimeSize = file("src/main/jniLibs/$relativePath").length()
                    check(symbolEntry.size > runtimeSize) {
                        "Expected an unstripped symbol library for $relativePath"
                    }
                }
            }
        }

        from(providers.provider {
            val configuredPath = mpvDebugObjectsZip.orNull
                ?: throw GradleException(
                    "Set -PmpvDebugObjectsZip=<path-to-debug-objs.zip> or " +
                        "TORVE_MPV_DEBUG_OBJECTS_ZIP.",
                )
            zipTree(rootProject.file(configuredPath))
        }) {
            include(expectedMpvRuntimeSha256.keys)
        }
        destinationDirectory.set(layout.buildDirectory.dir("outputs/native-debug-symbols/$variantName"))
        archiveFileName.set("native-debug-symbols.zip")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

registerNativeSymbolsTask("googleMobileRelease")
registerNativeSymbolsTask("googleTvRelease")
registerNativeSymbolsTask("amazonTvRelease")

tasks.register("packageGooglePlayNativeSymbols") {
    group = "distribution"
    description = "Packages native debug symbol zips for Google Play mobile and TV release bundles."
    dependsOn(
        "packageGoogleMobileReleaseNativeSymbols",
        "packageGoogleTvReleaseNativeSymbols",
    )
}

tasks.register("packageReleaseNativeSymbols") {
    group = "distribution"
    description = "Packages matching native debug symbols for Google Play and Amazon TV releases."
    dependsOn(
        "packageGoogleMobileReleaseNativeSymbols",
        "packageGoogleTvReleaseNativeSymbols",
        "packageAmazonTvReleaseNativeSymbols",
    )
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    // Activity
    implementation(libs.activity.compose)

    // Splash screen (Android 12+ native splash)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // AppCompat (locale switching)
    implementation(libs.appcompat)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // ExoPlayer (Media3) — fallback player when libmpv not available
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // FFmpeg extension — software audio decoding for codecs missing on device (e.g. MPEG-L2 on Fire TV)
    // Prebuilt by Jellyfin from upstream media3 source with all audio codecs enabled.
    // Bundles libffmpegJNI.so with statically-linked FFmpeg audio decoders.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // WorkManager — background tasks (notifications)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Android TV / Leanback
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0-rc02")

    // YouTube Player (in-app trailer playback)
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0")

    // ── Credential transfer (Phase 3) ──
    // Tink covers the Android API < 33 X25519 gap; the JVM JCA provider
    // only added X25519 in API 33. We use Tink's `subtle.X25519` for
    // keypair gen + ECDH, plus platform AES-GCM and HMAC-SHA256 for
    // HKDF (both available on every supported API).
    implementation(libs.tink.android)

    // ML Kit barcode scanning, standalone (no Play Services dep). Used
    // only by the credential-transfer scan surface; gated at runtime on
    // the device having a camera (TV form factor without camera hides
    // the scan button).
    implementation(libs.mlkit.barcode)

    // CameraX — preview + lifecycle binding for the QR scanner.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ── Google-only dependencies (excluded from Amazon builds) ──

    // Google Cast (Chromecast) — no GMS on Fire TV
    "googleImplementation"("com.google.android.gms:play-services-cast-framework:22.0.0")
    "googleImplementation"("androidx.mediarouter:mediarouter:1.7.0")

    // Glance — App Widgets — not supported on Fire TV
    "googleImplementation"("androidx.glance:glance-appwidget:1.1.1")
    "googleImplementation"("androidx.glance:glance-material3:1.1.1")

    // Firebase — requires GMS
    "googleImplementation"(platform("com.google.firebase:firebase-bom:33.7.0"))
    "googleImplementation"("com.google.firebase:firebase-crashlytics")
    "googleImplementation"("com.google.firebase:firebase-analytics")

    // Google Play Billing — Google flavor only
    // Google Play Integrity — Google flavor only. Used as a backend-verifiable
    // trust signal; failures never grant or block access locally.
    "googleImplementation"("com.google.android.play:integrity:1.4.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("io.ktor:ktor-websockets:3.0.3")

    // Debug
    debugImplementation(libs.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")

    // Android UI tests
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
