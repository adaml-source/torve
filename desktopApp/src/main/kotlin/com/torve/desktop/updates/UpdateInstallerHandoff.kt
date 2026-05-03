package com.torve.desktop.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Smallest meaningful "do more than view release" path: download the
 * appcast's `<enclosure>` installer to a temp file, optionally verify
 * its SHA-256 against the feed-supplied hash, and hand it off to the
 * OS launcher (`Desktop.open` on Windows opens Setup.exe / .msi with
 * Windows Installer; on macOS opens .dmg with the Mounter; on Linux
 * opens .deb with the system handler).
 *
 * What this is **not**:
 *   * Native WinSparkle / Sparkle integration (no UI animation, no
 *     delta updates, no rollback, no auto-relaunch). Sparkle on macOS
 *     and WinSparkle on Windows are large native dependencies and
 *     out of scope for this slice.
 *   * Authenticode / Developer ID signature verification of the
 *     downloaded installer. The OS does that during install - but if
 *     the feed is unsigned and HTTPS is broken at the user's network,
 *     the installer Torve hands off could be tampered. Real release
 *     channels MUST sign the installer (covered by the operator
 *     `signtool` step in WINDOWS_PACKAGING.md and the macOS
 *     `notarization {}` block in build.gradle.kts) so the OS-level
 *     verification catches it.
 *   * Auto-relaunch after install. The OS installer terminates Torve
 *     mid-install on Windows; users get a fresh launch from the
 *     Start menu.
 *
 * Contract:
 *   * Single-shot - one [start] call per [UpdateChecker.UpdateInfo].
 *   * Cancelable via the returned coroutine job.
 *   * Idempotent on the temp file: re-downloads cleanly if the prior
 *     attempt was interrupted; refuses to launch a partial file.
 *   * Never logs the resolved temp path or the URL at info level after
 *     handoff (the URL is in the feed so it's "public", but we don't
 *     leave it in user-visible logs once handed off).
 */
class UpdateInstallerHandoff(
    private val tempDirSupplier: () -> File = { File(System.getProperty("java.io.tmpdir")) },
    private val osLauncher: (File) -> Unit = ::defaultOsLauncher,
    private val urlOpener: (String) -> java.io.InputStream = ::defaultUrlOpener,
    private val osNameSupplier: () -> String = { System.getProperty("os.name") ?: "" },
) {

    sealed class Phase {
        data object Idle : Phase()
        data class Downloading(val bytesRead: Long, val totalBytes: Long?) : Phase()
        data object Verifying : Phase()
        data class HandedOff(val installer: File) : Phase()
        data class Failed(val reason: String) : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /**
     * Drive the full path. Suspends until the OS launcher has been
     * invoked or a failure fires. Returns the final phase.
     */
    suspend fun start(info: UpdateChecker.UpdateInfo): Phase = withContext(Dispatchers.IO) {
        val installerUrl = info.installerUrl
        if (installerUrl.isNullOrBlank()) {
            return@withContext fail("Update feed didn't include a direct installer URL.")
        }
        if (!supportsHandoffOn(osNameSupplier())) {
            return@withContext fail("Installer handoff not supported on this OS yet - use the View release link.")
        }

        // Refuse non-HTTPS to avoid handing off an unauthenticated
        // file. (Authenticode/Notarization is the real backstop, but
        // letting the connection itself be MITM'd is an own-goal.)
        if (!installerUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext fail("Refusing to download installer over non-HTTPS URL.")
        }

        val installerFile = stagedTempFile(info, installerUrl)
        // Best-effort delete of any prior partial download so we never
        // launch a half-written file.
        if (installerFile.exists()) installerFile.delete()

        // ── download ─────────────────────────────────────────────
        val downloadOk = runCatching {
            urlOpener(installerUrl).use { input ->
                installerFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                        _phase.value = Phase.Downloading(total, null)
                    }
                }
            }
            true
        }.getOrElse { t ->
            return@withContext fail("Download failed: ${t.message ?: t::class.simpleName}")
        }
        if (!downloadOk || !installerFile.exists() || installerFile.length() == 0L) {
            return@withContext fail("Download produced an empty file.")
        }

        // ── verify ───────────────────────────────────────────────
        val expectedSha = info.installerSha256?.lowercase()
        if (!expectedSha.isNullOrBlank()) {
            _phase.value = Phase.Verifying
            val actual = sha256Of(installerFile)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                installerFile.delete()
                return@withContext fail("SHA-256 mismatch on downloaded installer.")
            }
        }

        // ── hand off ─────────────────────────────────────────────
        runCatching { osLauncher(installerFile) }.getOrElse { t ->
            return@withContext fail("OS launcher rejected the installer: ${t.message ?: t::class.simpleName}")
        }
        val final = Phase.HandedOff(installerFile)
        _phase.value = final
        final
    }

    private fun fail(reason: String): Phase {
        val f = Phase.Failed(reason)
        _phase.value = f
        return f
    }

    /**
     * Picks a stable temp filename so a re-run replaces (rather than
     * accumulates) the prior download. Filename is derived from the
     * URL's last path segment, fall back to the version tag.
     */
    private fun stagedTempFile(info: UpdateChecker.UpdateInfo, url: String): File {
        val urlName = url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
        val safe = (urlName ?: "torve-${info.tag}.installer")
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return File(tempDirSupplier(), "torve-update-$safe")
    }

    /**
     * Hex-encoded SHA-256 of [file]. 64 KiB streaming so big installers
     * don't blow memory.
     */
    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Hand the downloaded installer to the OS.
         *
         * Originally just `Desktop.open(file)` on the assumption that
         * AWT's "let the OS handle it" call would route any platform-
         * native installer to the registered handler. Two problems
         * surfaced:
         *
         *   1. Inside Windows Sandbox the .msi filetype handler isn't
         *      registered the same way as on a normal install, so
         *      `Desktop.open` fails with "Unsupported URI content".
         *      Reproduced by B4 success-path smoke 2026-05-03.
         *   2. Some Java/AWT builds on real Windows treat `.msi` as a
         *      data-only association and refuse to invoke the
         *      Installer service, again throwing on `open()`.
         *
         * Fix: invoke `msiexec /i <path>` directly for Windows .msi
         * files. That hits Windows Installer regardless of file
         * association quirks. Other OS/extension combos still use
         * `Desktop.open` — we don't want shell-quoting logic on
         * paths we didn't pick.
         */
        /**
         * Returns the explicit launcher command for [file] on [osName],
         * or null when no special handling is needed (caller should
         * fall back to `Desktop.open`). Today the only special case is
         * Windows + .msi → `msiexec /i <file>`, because `Desktop.open`
         * fails on .msi inside Windows Sandbox and on some Java/AWT
         * builds with quirky file-association handling.
         */
        internal fun resolveLauncherCommand(file: File, osName: String): List<String>? {
            val n = osName.lowercase()
            val isWindows = "windows" in n
            val isMsi = file.name.endsWith(".msi", ignoreCase = true)
            return if (isWindows && isMsi) {
                listOf("msiexec.exe", "/i", file.absolutePath)
            } else {
                null
            }
        }

        internal fun defaultOsLauncher(file: File) {
            val cmd = resolveLauncherCommand(file, System.getProperty("os.name") ?: "")
            if (cmd != null) {
                // Inherit stdio so any error is visible in the launching
                // shell during dev; the Installer UI itself runs
                // detached. /i = install (or upgrade if same upgrade
                // code); we let Windows decide based on the MSI's
                // ProductCode/UpgradeCode metadata.
                ProcessBuilder(cmd).inheritIO().start()
                return
            }
            java.awt.Desktop.getDesktop().open(file)
        }

        internal fun defaultUrlOpener(url: String): java.io.InputStream {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Torve-UpdateInstallerHandoff/1.0",
                )
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                error("Download HTTP ${conn.responseCode}")
            }
            return conn.inputStream
        }

        /**
         * Today: Windows + macOS only. Linux package handlers vary too
         * much per-distro for the handoff to be reliable across
         * .deb / .rpm / AppImage; falls back to View release.
         */
        internal fun supportsHandoffOn(osName: String): Boolean {
            val n = osName.lowercase()
            return "windows" in n || "mac" in n || "darwin" in n
        }
    }
}
