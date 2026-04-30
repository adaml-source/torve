import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import org.gradle.api.GradleException

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

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.koin.core)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.vlcj)
    implementation(libs.qrcode.kotlin)
    implementation(libs.kotlinx.serialization.json)
    // NewPipeExtractor — pure-JVM YouTube URL resolver, used as the
    // first-line fallback in TrailerOverlay. YouTube periodically breaks
    // its parser; bump this pin when trailers stop playing. The
    // user-facing path also tries yt-dlp first (when installed) which is
    // the most reliable resolver in 2026.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.6")
    // Sentry — DSN is read from TORVE_SENTRY_DSN at runtime, so the SDK is
    // a no-op until ops sets it. We add the dependency unconditionally so we
    // never have a build matrix where crashes can land in a binary that has
    // nothing to send them with.
    implementation("io.sentry:sentry:7.18.1")
    implementation("io.sentry:sentry-kotlin-extensions:7.18.1")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

val bundledVlcDirectory = layout.projectDirectory.dir("runtime/windows/vlc")
val packagingChecklistDoc = layout.projectDirectory.file("WINDOWS_PACKAGING.md")

// Optional bundled libmpv runtime — see desktopApp/MPV_BUNDLING.md.
// `appResourcesRootDir` is already wired to `runtime/`, so anything
// dropped under `runtime/<os>/mpv/` ships with the package automatically;
// these tasks just verify and document the drop.
val bundledMpvWindowsDir = layout.projectDirectory.dir("runtime/windows/mpv")
val bundledMpvMacosDir = layout.projectDirectory.dir("runtime/macos/mpv")
val bundledMpvLinuxDir = layout.projectDirectory.dir("runtime/linux/mpv")

tasks.register("verifyWindowsPackagingPrereqs") {
    group = "distribution"
    description = "Checks Windows desktop packaging prerequisites for Torve."
    doLast {
        val javaHome = File(System.getProperty("java.home"))
        val jpackageExe = File(javaHome, "bin/jpackage.exe")
        if (!jpackageExe.exists()) {
            throw GradleException(
                "Windows desktop packaging requires a JDK with jpackage.exe. " +
                    "Current java.home: ${javaHome.absolutePath}. " +
                    "See ${packagingChecklistDoc.asFile.absolutePath}."
            )
        }

        // ── Bundled VLC runtime: hard gate ───────────────────────────
        // A release Torve package must ship a working VLC runtime; users
        // shouldn't have to install VLC, mpv, or set env vars. Set
        // `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1` to downgrade this to a
        // warning for local dev packaging dry-runs only.
        //
        // **Release-build override**: when `TORVE_RELEASE_BUILD=1` is
        // set the bypass is refused — release builds MUST have a
        // complete runtime even if a tired engineer typed
        // `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1` two terminals ago. CI
        // for `release/*` branches sets `TORVE_RELEASE_BUILD=1`.
        val allowMissing = System.getenv("TORVE_PACKAGE_ALLOW_MISSING_RUNTIME") == "1"
        val isReleaseBuild = System.getenv("TORVE_RELEASE_BUILD") == "1"
        if (allowMissing && isReleaseBuild) {
            throw GradleException(
                "TORVE_RELEASE_BUILD=1 is set; TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1 cannot be used " +
                    "for release builds. Stage the VLC runtime with desktopApp/scripts/" +
                    "stage-windows-vlc-runtime.{ps1,sh} and re-run.",
            )
        }
        val vlcDir = bundledVlcDirectory.asFile
        val problems = mutableListOf<String>()

        if (!vlcDir.exists()) {
            problems += "VLC runtime directory missing: ${vlcDir.absolutePath}"
        } else {
            listOf("libvlc.dll", "libvlccore.dll").forEach { lib ->
                if (!File(vlcDir, lib).exists()) {
                    problems += "Required VLC native library missing: $lib"
                }
            }
            val pluginsDir = File(vlcDir, "plugins")
            if (!pluginsDir.exists() || !pluginsDir.isDirectory) {
                problems += "VLC plugins/ directory missing under ${vlcDir.absolutePath}"
            } else {
                val pluginCount = pluginsDir.walkTopDown()
                    .count { it.isFile && it.name.endsWith(".dll", ignoreCase = true) }
                // VLC 3.x ships ~280 plugin DLLs out of the box; 100 is a
                // very forgiving floor that still catches the "I copied
                // libvlc.dll only" / "I copied just access/ and codec/"
                // truncation cases the previous floor of 30 missed.
                val pluginFloor = 100
                if (pluginCount < pluginFloor) {
                    problems += "VLC plugins/ directory only contains $pluginCount .dll files; " +
                        "expected at least $pluginFloor (a clean VLC 3.x install ships ~280). " +
                        "Re-copy the full plugins/ tree — partial drops break codecs at runtime."
                }
                // Spot-check that the required plugin families exist.
                // A pruned plugins/ tree compiles fine through jpackage
                // but ships a player that can't open most files.
                val requiredPluginFamilies = listOf("access", "codec", "demux", "video_output")
                val missingFamilies = requiredPluginFamilies.filter {
                    !File(pluginsDir, it).isDirectory
                }
                if (missingFamilies.isNotEmpty()) {
                    problems += "VLC plugin families missing: ${missingFamilies.joinToString()}. " +
                        "These subdirectories are required for working playback; copy the full plugins/ tree."
                }
            }
            // Per VLC's LGPL-2.1 obligations, a bundled distribution must
            // ship the upstream license notice. Accept either VLC's own
            // COPYING.txt or a renamed LICENSE-VLC.txt.
            val hasLicense = listOf("COPYING.txt", "LICENSE-VLC.txt", "LICENSE.txt")
                .any { File(vlcDir, it).exists() }
            if (!hasLicense) {
                problems += "VLC license notice missing under ${vlcDir.absolutePath}. " +
                    "Drop COPYING.txt (or LICENSE-VLC.txt) from the upstream VLC distribution."
            }
        }

        // ── Bundled MPV runtime: licensing gate ──────────────────────
        // appResourcesRootDir copies the entire `runtime/` tree into the
        // installer, so any binaries left in `runtime/windows/mpv/` ship
        // whether MPV is the active engine or not. MPV (libmpv) is
        // LGPL-2.1+; bundling it without the upstream COPYING is a
        // licensing leak. Default state today is "no MPV bundled" — this
        // check just refuses to release if someone re-stages MPV
        // binaries without a license file.
        val mpvDir = bundledMpvWindowsDir.asFile
        val mpvBinaries = listOf("libmpv-2.dll", "mpv-2.dll", "mpv.exe")
            .map { File(mpvDir, it) }
            .filter { it.exists() }
        if (mpvBinaries.isNotEmpty()) {
            val hasMpvLicense = listOf("LICENSE-MPV.txt", "COPYING", "COPYING.txt", "LICENSE.txt")
                .any { File(mpvDir, it).exists() }
            if (!hasMpvLicense) {
                problems += "MPV binary bundled at ${mpvDir.absolutePath} (${mpvBinaries.joinToString(", ") { it.name }}) " +
                    "without a license notice. libmpv is LGPL-2.1+; drop LICENSE-MPV.txt or remove the binaries. " +
                    "Default release shape is no MPV bundled — see runtime/windows/mpv/README.txt."
            }
        }

        if (problems.isNotEmpty()) {
            val summary = buildString {
                appendLine("Bundled playback runtime is not release-ready:")
                problems.forEach { appendLine("  • $it") }
                appendLine()
                appendLine("Easiest path — run the staging script:")
                appendLine("  Windows: powershell desktopApp/scripts/stage-windows-vlc-runtime.ps1")
                appendLine("  Linux/macOS CI: TORVE_VLC_PORTABLE=<dir> desktopApp/scripts/stage-windows-vlc-runtime.sh")
                appendLine()
                appendLine("Manual path — stage VLC runtime under ${vlcDir.absolutePath}:")
                appendLine("  - Copy libvlc.dll, libvlccore.dll, and plugins/ from a clean")
                appendLine("    install of VLC 64-bit (e.g. C:\\Program Files\\VideoLAN\\VLC).")
                appendLine("  - Drop the upstream COPYING.txt next to libvlc.dll.")
                appendLine("If you also bundle MPV (optional), drop LICENSE-MPV.txt next to libmpv-2.dll.")
                appendLine("See ${layout.projectDirectory.file("WINDOWS_PACKAGING.md").asFile.absolutePath}.")
                appendLine()
                appendLine("To bypass for a local packaging dry-run only, set the env var")
                appendLine("  TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1")
                appendLine("Released builds MUST NOT bypass this check.")
                appendLine("(Refused automatically when TORVE_RELEASE_BUILD=1.)")
            }
            if (allowMissing) {
                logger.warn(summary)
                logger.warn("TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1 is set — continuing without a release-ready runtime.")
            } else {
                throw GradleException(summary)
            }
        }
    }
}

tasks.register("printWindowsPackagingChecklist") {
    group = "distribution"
    description = "Prints the Torve Windows packaging checklist location."
    doLast {
        println("Torve Windows packaging checklist: ${packagingChecklistDoc.asFile.absolutePath}")
        println("Bundled VLC drop location: ${bundledVlcDirectory.asFile.absolutePath}")
    }
}

tasks.register("verifyMpvRuntime") {
    group = "distribution"
    description = "Reports whether a bundled libmpv runtime is staged for the current host."
    doLast {
        fun hasLib(dir: File, vararg names: String): Boolean =
            dir.exists() && names.any { File(dir, it).exists() }
        val win = hasLib(bundledMpvWindowsDir.asFile, "mpv-2.dll", "libmpv-2.dll")
        val mac = hasLib(bundledMpvMacosDir.asFile, "libmpv.2.dylib", "libmpv.dylib")
        val linux = hasLib(bundledMpvLinuxDir.asFile, "libmpv.so.2", "libmpv.so")
        println("Bundled libmpv staging:")
        println("  windows: ${if (win) "OK" else "missing"} (${bundledMpvWindowsDir.asFile.absolutePath})")
        println("  macos:   ${if (mac) "OK" else "missing"} (${bundledMpvMacosDir.asFile.absolutePath})")
        println("  linux:   ${if (linux) "OK" else "missing"} (${bundledMpvLinuxDir.asFile.absolutePath})")
        println("MPV is optional — Torve falls back to VLC when absent. " +
            "See desktopApp/MPV_BUNDLING.md for drop instructions.")
    }
}

tasks.register("generateSampleAppcast") {
    group = "distribution"
    description = "Emits a sample Sparkle/WinSparkle-compatible appcast.xml. " +
        "Host the result at a public URL and set TORVE_UPDATE_FEED to it."
    doLast {
        val out = layout.projectDirectory.file("release/appcast.sample.xml").asFile
        out.parentFile?.mkdirs()
        val version = "1.0.7"
        val pubDate = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            .withZone(ZoneId.of("UTC"))
            .format(Instant.now())
        out.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
              <channel>
                <title>Torve</title>
                <link>https://torve.app/</link>
                <description>Torve desktop release feed.</description>
                <language>en</language>
                <item>
                  <title>Torve $version</title>
                  <pubDate>$pubDate</pubDate>
                  <link>https://github.com/your-org/torve/releases/tag/v$version</link>
                  <description><![CDATA[
                    <ul>
                      <li>Replace these notes with the real changelog.</li>
                      <li>Markdown HTML works inside CDATA.</li>
                    </ul>
                  ]]></description>
                  <enclosure
                    url="https://your-cdn.example/torve-$version.dmg"
                    sparkle:version="$version"
                    sparkle:shortVersionString="$version"
                    length="100000000"
                    type="application/octet-stream"
                    />
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        println("Wrote sample appcast to: ${'$'}{out.absolutePath}")
        println("Edit the version, URL, and notes, then host on your CDN.")
        println("Point TORVE_UPDATE_FEED at the hosted URL to enable in-app update detection.")
    }
}

listOf(
    "createDistributable",
    "packageDistributionForCurrentOS",
    "packageExe",
    "packageMsi",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn("verifyWindowsPackagingPrereqs")
    }
}

compose.desktop {
    application {
        mainClass = "com.torve.desktop.MainKt"
        // Release channel is env-driven so a single build script
        // produces stable / beta / internal artifacts. CI sets
        // TORVE_RELEASE_CHANNEL per pipeline; defaults to
        // internal-preview when unset.
        val releaseChannel = providers.environmentVariable("TORVE_RELEASE_CHANNEL")
            .orElse("internal-preview")
            .get()
        jvmArgs += listOf(
            "-DTMDB_API_KEY=${readTmdbApiKey()}",
            "-Dtorve.desktop.appName=Torve",
            "-Dtorve.desktop.version=1.0.6",
            "-Dtorve.desktop.vendor=Torve",
            "-Dtorve.desktop.description=Torve desktop for Windows with embedded VLC playback.",
            "-Dtorve.desktop.channel=$releaseChannel",
        )

        nativeDistributions {
            appResourcesRootDir.set(layout.projectDirectory.dir("runtime"))
            // All three host families. The Compose Gradle plugin only
            // produces formats it can actually build on the current host
            // (Windows can't sign a DMG; Linux can't make MSIs), but
            // listing them all keeps a single source of truth so CI
            // matrix jobs don't each have to redeclare.
            targetFormats(
                // Windows
                TargetFormat.Exe,
                TargetFormat.Msi,
                // macOS — DMG is the canonical install bundle. Notarization
                // requires Developer ID + altool; not part of this task,
                // see release/ for the wiring once certs are provisioned.
                TargetFormat.Dmg,
                // Linux — Deb covers Debian/Ubuntu, AppImage is the
                // distro-agnostic single-file path.
                TargetFormat.Deb,
                TargetFormat.AppImage,
            )
            packageName = "Torve"
            packageVersion = "1.0.6"
            vendor = "Torve"
            description = "Torve cross-platform media hub. Browse. Pick. Watch."
            copyright = "© 2026 Torve"
            licenseFile.set(layout.projectDirectory.file("LICENSE"))

            windows {
                // Per-machine install (visible in Add/Remove Programs for
                // every user). Per-user installs are easier to push but
                // make the auto-update story messier.
                perUserInstall = false
                menuGroup = "Torve"
                shortcut = true
                dirChooser = true
                // Stable upgrade UUID — required for MSI to recognise an
                // upgrade vs a fresh install. Generate once, never change
                // for the lifetime of the product.
                upgradeUuid = "1f2a4b80-3a87-4d52-9c6f-9b9c2d1f5b30"
            }

            macOS {
                bundleID = "app.torve.desktop"
                appCategory = "public.app-category.video"
                // Code signing wired through env vars at packaging time:
                //   TORVE_MAC_SIGN_IDENTITY (Developer ID Application: ...)
                //   TORVE_MAC_NOTARIZATION_USER / TORVE_MAC_NOTARIZATION_PWD
                // Compose plugin reads these from the env automatically
                // when present; no further config needed here.
                signing {
                    sign.set(System.getenv("TORVE_MAC_SIGN_IDENTITY") != null)
                    System.getenv("TORVE_MAC_SIGN_IDENTITY")?.let {
                        identity.set(it)
                    }
                }
                notarization {
                    System.getenv("TORVE_MAC_NOTARIZATION_USER")?.let {
                        appleID.set(it)
                    }
                    System.getenv("TORVE_MAC_NOTARIZATION_PWD")?.let {
                        password.set(it)
                    }
                }
            }

            linux {
                packageName = "torve"
                debMaintainer = "release@torve.app"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                shortcut = true
            }
        }
    }
}
