import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.torve.android"
    compileSdk = 36
    val pandaBaseUrl = providers.gradleProperty("pandaBaseUrl")
        .orElse(providers.environmentVariable("TORVE_PANDA_BASE_URL"))
        .orElse("https://panda.torve.app")

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

    val baseVersionCode = 52

    defaultConfig {
        applicationId = "com.torve.app"
        minSdk = 24
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "1.0.43"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-config.pro")
        manifestPlaceholders["torveAllowBackup"] = "true"

        // Default: both ARM ABIs.  Pass -PabiOverride=arm64-v8a for fast dev builds.
        ndk {
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
    }

    flavorDimensions += listOf("store", "formFactor")
    productFlavors {
        create("google") {
            dimension = "store"
            buildConfigField("Boolean", "HAS_BILLING", "true")
        }
        create("amazon") {
            dimension = "store"
            // Different applicationId so it can coexist with Google Play version
            applicationIdSuffix = ".amazon"
            buildConfigField("Boolean", "HAS_BILLING", "true")
        }
        create("mobile") {
            dimension = "formFactor"
            versionCode = 10000 + baseVersionCode
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
            buildConfigField("Boolean", "ALLOW_DEBUG_PREMIUM_BYPASS", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("Boolean", "ALLOW_DEBUG_PREMIUM_BYPASS", "false")
            manifestPlaceholders["torveAllowBackup"] = "false"
            // Bundle native debug symbols (libmpv, FFmpeg) so Play Console
            // can symbolicate native crashes and ANRs. FULL includes full
            // DWARF info (~50–200 MB extra in the AAB but stripped from
            // the installed APK); SYMBOL_TABLE is smaller but only gives
            // function names with no source mapping.
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

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
    "googleImplementation"("com.android.billingclient:billing-ktx:7.1.1")

    // Amazon Appstore IAP — Amazon flavor only
    "amazonImplementation"("com.amazon.device:amazon-appstore-sdk:3.0.5")

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
