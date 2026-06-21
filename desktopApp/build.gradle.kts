import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
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

fun hostOsName(): String = System.getProperty("os.name", "").lowercase(Locale.US)

fun isWindowsHost(): Boolean = "win" in hostOsName()

fun isLinuxHost(): Boolean {
    val os = hostOsName()
    return "linux" in os || "nux" in os || "nix" in os
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
    implementation(libs.kotlinx.serialization.json)
    // NewPipeExtractor â€” pure-JVM YouTube URL resolver, used as the
    // first-line fallback in TrailerOverlay. YouTube periodically breaks
    // its parser; bump this pin when trailers stop playing. The
    // user-facing path also tries yt-dlp first (when installed) which is
    // the most reliable resolver in 2026.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.6")
    // Sentry â€” DSN is read from TORVE_SENTRY_DSN at runtime, so the SDK is
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

// Optional bundled libmpv runtime â€” see desktopApp/MPV_BUNDLING.md.
// `appResourcesRootDir` is already wired to `runtime/`, so anything
// dropped under `runtime/<os>/mpv/` ships with the package automatically;
// these tasks just verify and document the drop.
val bundledMpvWindowsDir = layout.projectDirectory.dir("runtime/windows/mpv")
val bundledMpvMacosDir = layout.projectDirectory.dir("runtime/macos/mpv")
val bundledMpvLinuxDir = layout.projectDirectory.dir("runtime/linux/mpv")
val bundledVlcLinuxDir = layout.projectDirectory.dir("runtime/linux/vlc")

tasks.register("verifyWindowsPackagingPrereqs") {
    group = "distribution"
    description = "Checks Windows desktop packaging prerequisites for Torve."
    doLast {
        // Resolve a JDK with jpackage.exe via the same chain
        // packageMsiCloseApp uses (TORVE_JPACKAGE_JDK env var first,
        // then $JAVA_HOME, then auto-discovered JDK 21+ installs).
        // Compose Desktop's javaHome also points here, so the gate
        // matches what the actual packaging will use rather than
        // checking the daemon JVM (which is JBR and doesn't ship
        // jdk.jpackage).
        val jpackageExe = runCatching { locateJpackage() }.getOrNull()
        if (jpackageExe == null) {
            throw GradleException(
                "Windows desktop packaging requires a JDK with jpackage.exe. " +
                    "JBR (the Android Studio bundled JDK) does NOT ship " +
                    "jdk.jpackage. Install JDK 21+ from https://adoptium.net " +
                    "or set TORVE_JPACKAGE_JDK to a JDK root that has " +
                    "bin/jpackage.exe. See ${packagingChecklistDoc.asFile.absolutePath}."
            )
        }

        // â”€â”€ Bundled VLC runtime: hard gate â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // A release Torve package must ship a working VLC runtime; users
        // shouldn't have to install VLC, mpv, or set env vars. Set
        // `TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1` to downgrade this to a
        // warning for local dev packaging dry-runs only.
        //
        // **Release-build override**: when `TORVE_RELEASE_BUILD=1` is
        // set the bypass is refused â€” release builds MUST have a
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
                        "Re-copy the full plugins/ tree â€” partial drops break codecs at runtime."
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

        // â”€â”€ Bundled MPV runtime: licensing gate â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // appResourcesRootDir copies the entire `runtime/` tree into the
        // installer, so any binaries left in `runtime/windows/mpv/` ship
        // whether MPV is the active engine or not. MPV (libmpv) is
        // LGPL-2.1+; bundling it without the upstream COPYING is a
        // licensing leak. Default state today is "no MPV bundled" â€” this
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
                    "Default release shape is no MPV bundled â€” see runtime/windows/mpv/README.txt."
            }
        }

        if (problems.isNotEmpty()) {
            val summary = buildString {
                appendLine("Bundled playback runtime is not release-ready:")
                problems.forEach { appendLine("  â€¢ $it") }
                appendLine()
                appendLine("Easiest path â€” run the staging script:")
                appendLine("  Windows: powershell desktopApp/scripts/stage-windows-vlc-runtime.ps1")
                appendLine("  Linux/macOS CI: TORVE_VLC_PORTABLE=<dir> desktopApp/scripts/stage-windows-vlc-runtime.sh")
                appendLine()
                appendLine("Manual path â€” stage VLC runtime under ${vlcDir.absolutePath}:")
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
                logger.warn("TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1 is set â€” continuing without a release-ready runtime.")
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

tasks.register("verifyLinuxPackagingPrereqs") {
    group = "distribution"
    description = "Checks Linux desktop packaging prerequisites for Torve."
    doLast {
        if (!isLinuxHost()) {
            throw GradleException(
                "Linux desktop packaging must run on a Linux host. " +
                    "Use a Linux VM/CI runner for :desktopApp:packageDeb or :desktopApp:packageAppImage.",
            )
        }

        val jpackage = locateJpackageForCurrentHost()
        if (jpackage == null) {
            throw GradleException(
                "Linux desktop packaging requires a full JDK with jpackage. " +
                    "Install JDK 21+ or set TORVE_JPACKAGE_JDK to a JDK root that has bin/jpackage.",
            )
        }

        val missingTools = listOf("dpkg-deb", "fakeroot", "file").filterNot(::isCommandAvailable)
        if (missingTools.isNotEmpty()) {
            throw GradleException(
                "Linux desktop packaging tools missing: ${missingTools.joinToString()}. " +
                    "On Ubuntu install them with: sudo apt install dpkg fakeroot file.",
            )
        }

        val hasBundledMpv = bundledMpvLinuxDir.asFile
            .let { dir -> dir.isDirectory && listOf("libmpv.so.2", "libmpv.so").any { File(dir, it).exists() } }
        val hasBundledVlc = bundledVlcLinuxDir.asFile
            .let { dir -> dir.isDirectory && listOf("libvlc.so", "libvlccore.so").any { File(dir, it).exists() } }
        if (!hasBundledMpv && !hasBundledVlc) {
            logger.warn(
                "No bundled Linux playback runtime found under runtime/linux/{mpv,vlc}. " +
                    "This is acceptable for beta packaging only; Linux smoke hosts must install mpv/libmpv or VLC.",
            )
        }

        println("Linux packaging prerequisites OK. jpackage=${jpackage.absolutePath}")
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
        println("MPV is optional â€” Torve falls back to VLC when absent. " +
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
    "packageExe",
    "packageMsi",
    "packageReleaseExe",
    "packageReleaseMsi",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn("verifyWindowsPackagingPrereqs")
    }
}

listOf(
    "packageDeb",
    "packageAppImage",
    "packageReleaseDeb",
    "packageReleaseAppImage",
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn("verifyLinuxPackagingPrereqs")
    }
}

if (isLinuxHost()) {
    listOf(
        "packageDistributionForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
    ).forEach { taskName ->
        tasks.matching { it.name == taskName }.configureEach {
            dependsOn("verifyLinuxPackagingPrereqs")
        }
    }
}

// Custom MSI build that injects a `util:CloseApplication` element via a
// hand-rolled WiX template. Compose Desktop's bundled `packageMsi` task
// has no hook for `--resource-dir`, so we drive `jpackage` directly here
// after the app image has been produced by `createDistributable`. The
// resulting MSI's Restart Manager force-closes any running Torve.exe
// before file copy, which fixes the "Files in Use" dialog the in-app
// updater handoff would otherwise trigger on upgrade.
//
// Use this task — NOT `packageMsi` — for any release that ships the
// updater. CI for `release/*` branches must call `packageMsiCloseApp`.
tasks.register("packageMsiCloseApp") {
    group = "distribution"
    description = "Builds the Torve MSI with a util:CloseApplication element so the in-app updater can upgrade without 'Files in Use' prompts. Override version with -PtorveMsiVersion=X.Y.Z."
    dependsOn("verifyWindowsPackagingPrereqs", "createDistributable")
    // Single source of truth shared with compose.desktop.application
    // (jvmArgs `-Dtorve.desktop.version` + packageVersion). Override per
    // build via `-PtorveMsiVersion=X.Y.Z`.
    val torveVersion = (project.findProperty("torveMsiVersion") as String?) ?: "1.0.6"
    val wixResourceDir = layout.projectDirectory.dir("wix-resources")
    val licenseFile = layout.projectDirectory.file("LICENSE")
    val iconFile = layout.projectDirectory.file("src/main/resources/torve.ico")
    val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/Torve")
    val outDir = layout.buildDirectory.dir("compose/binaries/main-closeapp/msi")
    inputs.dir(wixResourceDir)
    inputs.file(licenseFile)
    inputs.file(iconFile)
    // The app image is what jpackage actually consumes via --app-image.
    // Without this declaration, gradle thinks the task is UP-TO-DATE
    // when only Kotlin sources change (createDistributable rebuilds the
    // app image, but packageMsiCloseApp wouldn't notice). Result: a
    // BUILD SUCCESSFUL that quietly serves a stale MSI.
    inputs.dir(appImageDir).withPropertyName("appImage").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("torveVersion", torveVersion)
    inputs.property("torveUpdateFeed", project.findProperty("torveUpdateFeed") as String? ?: "")
    outputs.dir(outDir)

    doLast {
        val outDirFile = outDir.get().asFile
        outDirFile.mkdirs()

        val appImage = layout.buildDirectory
            .dir("compose/binaries/main/app/Torve")
            .get().asFile
        if (!appImage.exists()) {
            throw GradleException(
                "Compose Desktop app image missing at ${appImage.absolutePath}. " +
                    "Run :desktopApp:createDistributable first.",
            )
        }

        val jpackage = locateJpackage()
        val args = mutableListOf(
            jpackage.absolutePath,
            "--type", "msi",
            "--app-image", appImage.absolutePath,
            "--dest", outDirFile.absolutePath,
            "--name", "Torve",
            "--app-version", torveVersion,
            "--vendor", "Torve",
            "--description", "Torve cross-platform media hub. Browse. Pick. Watch.",
            "--copyright", "© 2026 Torve",
            "--license-file", layout.projectDirectory.file("LICENSE").asFile.absolutePath,
            "--resource-dir", wixResourceDir.asFile.absolutePath,
            "--icon", layout.projectDirectory
                .file("src/main/resources/torve.ico").asFile.absolutePath,
            "--win-menu",
            "--win-menu-group", "Torve",
            "--win-shortcut",
            "--win-shortcut-prompt",
            "--win-dir-chooser",
            "--win-upgrade-uuid", "1f2a4b80-3a87-4d52-9c6f-9b9c2d1f5b30",
            // NOTE: omitting --win-per-user-install gives a perMachine
            // install, matching `perUserInstall = false` in the Compose
            // windows{} block. Required so the elevated MSI can close
            // any running Torve.exe via util:CloseApplication.
        )

        // jpackage needs WiX 3.x's candle.exe + light.exe on PATH. The
        // Compose Desktop plugin already downloads WiX 3.11 to
        // <rootProject>/build/wix311 (see :downloadWix / :unzipWix);
        // we prepend that to PATH so jpackage finds the tools without
        // requiring a system-wide WiX install.
        val wixDir = rootProject.layout.buildDirectory
            .dir("wix311")
            .get().asFile
        if (!wixDir.resolve("candle.exe").exists()) {
            throw GradleException(
                "WiX tools missing at ${wixDir.absolutePath}. " +
                    "Run :downloadWix / :unzipWix first (these run automatically " +
                    "via createDistributable's normal pipeline; if you see this, " +
                    "the Compose Desktop WiX bundle download was skipped).",
            )
        }

        logger.lifecycle("Running jpackage to build MSI with CloseApplication: ${args.drop(1).joinToString(" ")}")
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .also { pb ->
                val env = pb.environment()
                val existingPath = env["PATH"] ?: env["Path"] ?: ""
                env["PATH"] = "${wixDir.absolutePath}${File.pathSeparator}$existingPath"
            }
            .start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { logger.lifecycle("[jpackage] $it") }
        }
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("jpackage failed with exit code $exit")
        }

        val expectedMsi = outDirFile.resolve("Torve-$torveVersion.msi")
        val produced = expectedMsi.takeIf { it.exists() }
            ?: outDirFile.listFiles { f -> f.name.endsWith(".msi") }
                ?.maxByOrNull { it.lastModified() }
        if (produced == null) {
            throw GradleException("jpackage finished but no .msi found in ${outDirFile.absolutePath}")
        }
        logger.lifecycle("packageMsiCloseApp produced: ${produced.absolutePath}")
    }
}

// Locate a JDK that ships jpackage. JBR (the Android Studio bundled JDK
// used for everyday gradle work) does NOT include the jdk.jpackage
// module, so we fall back to a discovered JDK 21+ install. Override
// with TORVE_JPACKAGE_JDK if you have a non-default location.
fun locateJpackage(): File {
    val override = System.getenv("TORVE_JPACKAGE_JDK")
    if (!override.isNullOrBlank()) {
        val candidate = File(override).resolve("bin/jpackage.exe")
        if (candidate.exists()) return candidate
        throw GradleException("TORVE_JPACKAGE_JDK is set but $candidate does not exist")
    }
    val javaHome = File(System.getProperty("java.home"))
    val onPath = javaHome.resolve("bin/jpackage.exe")
    if (onPath.exists()) return onPath
    val pf = System.getenv("ProgramFiles") ?: "C:/Program Files"
    val candidates = listOf(
        File(pf, "Java"),
        File(pf, "Eclipse Adoptium"),
        File(pf, "Microsoft"),
    )
    for (base in candidates) {
        if (!base.exists()) continue
        val match = base.listFiles()
            ?.filter { it.isDirectory && it.resolve("bin/jpackage.exe").exists() }
            ?.maxByOrNull { it.name }
        if (match != null) return match.resolve("bin/jpackage.exe")
    }
    throw GradleException(
        "No JDK with jpackage.exe found. JBR doesn't ship jdk.jpackage. " +
            "Install JDK 21+ from https://adoptium.net or set TORVE_JPACKAGE_JDK.",
    )
}

fun locateJpackageForCurrentHost(): File? {
    val executable = if (isWindowsHost()) "jpackage.exe" else "jpackage"
    val override = System.getenv("TORVE_JPACKAGE_JDK")?.takeIf { it.isNotBlank() }
    if (override != null) {
        return File(override).resolve("bin/$executable").takeIf { it.exists() }
    }
    val javaHome = File(System.getProperty("java.home"))
    javaHome.resolve("bin/$executable").takeIf { it.exists() }?.let { return it }
    return findCommandOnPath(executable)
}

fun isCommandAvailable(command: String): Boolean = findCommandOnPath(command) != null

fun findCommandOnPath(command: String): File? {
    val path = System.getenv("PATH").orEmpty()
    if (path.isBlank()) return null
    return path.split(File.pathSeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(it, command) }
        .firstOrNull { it.exists() && it.canExecute() }
}

compose.desktop {
    application {
        mainClass = "com.torve.desktop.MainKt"
        // Compose Desktop's checkRuntime + packaging pipeline calls
        // jpackage. JBR (Android Studio's bundled JDK) does NOT ship
        // jdk.jpackage, so the build fails on a fresh daemon unless we
        // explicitly point at a JDK 21+ install that does. Honors the
        // TORVE_JPACKAGE_JDK env var first; falls back to a sensible
        // default for the local dev box; null lets Compose use the
        // toolchain (works only when the toolchain happens to have
        // jpackage, e.g. a non-JBR JDK 17).
        val jpackageJdk = System.getenv("TORVE_JPACKAGE_JDK")
            ?: listOf(
                "C:/Program Files/Java/jdk-21.0.10",
                "C:/Program Files/Java/jdk-21",
                "C:/Program Files/Eclipse Adoptium/jdk-21.0.4.7-hotspot",
            ).firstOrNull { File(it, "bin/jpackage.exe").exists() }
        if (jpackageJdk != null) {
            javaHome = jpackageJdk
        }
        // Release channel is env-driven so a single build script
        // produces stable / beta / internal artifacts. CI sets
        // TORVE_RELEASE_CHANNEL per pipeline; defaults to
        // internal-preview when unset.
        val releaseChannel = providers.environmentVariable("TORVE_RELEASE_CHANNEL")
            .orElse("internal-preview")
            .get()
        // Single source of truth for the desktop app version. Overridable
        // per-build via `-PtorveMsiVersion=X.Y.Z` so packaging is idempotent
        // without having to edit this file each release. Same property is
        // consumed by the packageMsiCloseApp task and the Compose Desktop
        // packageVersion below, so the JVM-reported version, the MSI's
        // ProductVersion, and the in-app About panel all stay in lockstep.
        val torveAppVersion = (project.findProperty("torveMsiVersion") as String?) ?: "1.0.6"
        // Auto-update feed URL — baked into the packaged build so the
        // in-app updater works out of the box for end users without
        // any TORVE_UPDATE_FEED env-var ceremony. The runtime resolver
        // (UpdateChecker.resolveDefaultFeed) still prefers the env var
        // when set, so dev / QA can point a packaged build at a
        // Sandbox cloudflared tunnel or staging feed without rebuilding.
        //
        // Set at build time via `-PtorveUpdateFeed=…` or the
        // TORVE_UPDATE_FEED env var. Empty string by default — dev
        // builds with no feed configured stay no-op (no ping).
        val updateFeedUrl = providers.environmentVariable("TORVE_UPDATE_FEED")
            .orElse(providers.gradleProperty("torveUpdateFeed"))
            .orElse("")
            .get()
        val updateRepo = providers.environmentVariable("TORVE_UPDATE_REPO")
            .orElse(providers.gradleProperty("torveUpdateRepo"))
            .orElse("")
            .get()
        jvmArgs += listOf(
            "-DTMDB_API_KEY=${readTmdbApiKey()}",
            "-Dtorve.desktop.appName=Torve",
            "-Dtorve.desktop.version=$torveAppVersion",
            "-Dtorve.desktop.vendor=Torve",
            "-Dtorve.desktop.description=Torve desktop for Windows with embedded VLC playback.",
            "-Dtorve.desktop.channel=$releaseChannel",
            "-Dtorve.update.feed=$updateFeedUrl",
            "-Dtorve.update.repo=$updateRepo",
            // Java's HttpURLConnection pools max 5 connections per host
            // by default. With ~32 parallel image loads on a detail
            // page (cast circles + Related rail + backdrop + logo +
            // episode posters), 27 sit in queue waiting for a slot.
            // 12s read timeout per slot = 1+ minute total wait. 64
            // simultaneous connections is well within the OS limit and
            // matches what HTTP/2 clients negotiate.
            "-Dhttp.maxConnections=64",
            "-Dhttp.keepAlive=true",
            // sun.net.http defaults reuse an idle connection only if
            // it's been idle <5s; bump so connections stay warm across
            // a typical page-load burst without re-handshaking TLS.
            "-Dhttp.keepAlive.timeout=30",
            // -splash:<path> is JDK's built-in launch-time splash. The
            // image is shown by the launcher before any class is loaded,
            // so it covers the JVM init + Compose first-frame gap that
            // otherwise leaves the user staring at an empty desktop for
            // ~350ms. The image lives in runtime/common/torve-splash.png;
            // appResourcesRootDir is wired to `runtime/`, and Compose
            // Desktop's jpackage layout places those files under
            // \$APPDIR/resources/ (verified by extracting the MSI:
            // Torve\app\resources\torve-splash.png).
            // The splash auto-closes when the main Window becomes visible.
            "-splash:${'$'}APPDIR/resources/torve-splash.png",
        )

        nativeDistributions {
            appResourcesRootDir.set(layout.projectDirectory.dir("runtime"))
            // java.sql       - SQLDelight + JDBC drivers
            // java.net.http  - HttpClient used by Newznab / TorBox NZB clients
            //                  (regression: B4 smoke 2026-05-03 caught a
            //                  NoClassDefFoundError post-wizard because this
            //                  module was missing from the runtime image)
            // jdk.crypto.ec  - Elliptic-curve TLS cipher suites for HTTPS to
            //                  ngrok / GitHub / generic CDNs
            // jdk.unsupported - sun.misc.Unsafe used transitively by some libs
            modules("java.sql", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")
            // All three host families. The Compose Gradle plugin only
            // produces formats it can actually build on the current host
            // (Windows can't sign a DMG; Linux can't make MSIs), but
            // listing them all keeps a single source of truth so CI
            // matrix jobs don't each have to redeclare.
            targetFormats(
                // Windows
                TargetFormat.Exe,
                TargetFormat.Msi,
                // macOS â€” DMG is the canonical install bundle. Notarization
                // requires Developer ID + altool; not part of this task,
                // see release/ for the wiring once certs are provisioned.
                TargetFormat.Dmg,
                // Linux â€” Deb covers Debian/Ubuntu, AppImage is the
                // distro-agnostic single-file path.
                TargetFormat.Deb,
                TargetFormat.AppImage,
            )
            packageName = "Torve"
            packageVersion = torveAppVersion
            vendor = "Torve"
            description = "Torve cross-platform media hub. Browse. Pick. Watch."
            copyright = "Â© 2026 Torve"
            licenseFile.set(layout.projectDirectory.file("LICENSE"))

            windows {
                msiPackageVersion = torveAppVersion
                exePackageVersion = torveAppVersion
                // Per-machine install (visible in Add/Remove Programs for
                // every user). Per-user installs are easier to push but
                // make the auto-update story messier.
                perUserInstall = false
                menuGroup = "Torve"
                shortcut = true
                dirChooser = true
                // Stable upgrade UUID â€” required for MSI to recognise an
                // upgrade vs a fresh install. Generate once, never change
                // for the lifetime of the product.
                upgradeUuid = "1f2a4b80-3a87-4d52-9c6f-9b9c2d1f5b30"
                iconFile.set(layout.projectDirectory.file("src/main/resources/torve.ico"))
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


