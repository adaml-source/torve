package com.torve.desktop.updates

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the contract surface that the UpdateBanner relies on:
 *
 *   * non-HTTPS URLs are refused
 *   * empty installer URLs are refused
 *   * unsupported OSes (today: Linux) fall back to the View-release path
 *   * SHA-256 mismatch fails before launching the installer
 *   * happy path lands in HandedOff and actually invokes the launcher
 *
 * Uses injected `osLauncher` / `urlOpener` / `osNameSupplier` /
 * `tempDirSupplier` so it never touches the network or pops a real
 * Windows Installer dialog under CI.
 */
class UpdateInstallerHandoffTest {

    @Test
    fun emptyInstallerUrlReturnsFailed() : Unit = runBlocking {
        val handoff = UpdateInstallerHandoff(
            tempDirSupplier = ::tempDir,
            osLauncher = { error("should not launch") },
            urlOpener = { error("should not download") },
            osNameSupplier = { "Windows 11" },
        )

        val phase = handoff.start(infoWith(installerUrl = null))

        assertTrue(phase is UpdateInstallerHandoff.Phase.Failed, "phase=$phase")
        assertTrue("direct installer URL" in phase.reason, "reason=${phase.reason}")
    }

    @Test
    fun nonHttpsUrlReturnsFailed() : Unit = runBlocking {
        val handoff = UpdateInstallerHandoff(
            tempDirSupplier = ::tempDir,
            osLauncher = { error("should not launch") },
            urlOpener = { error("should not download") },
            osNameSupplier = { "Windows 11" },
        )

        val phase = handoff.start(infoWith(installerUrl = "http://insecure.example/torve.exe"))

        assertTrue(phase is UpdateInstallerHandoff.Phase.Failed, "phase=$phase")
        assertTrue("non-HTTPS" in phase.reason, "reason=${phase.reason}")
    }

    @Test
    fun unsupportedOsReturnsFailed() : Unit = runBlocking {
        val handoff = UpdateInstallerHandoff(
            tempDirSupplier = ::tempDir,
            osLauncher = { error("should not launch") },
            urlOpener = { error("should not download") },
            osNameSupplier = { "Linux" },
        )

        val phase = handoff.start(infoWith(installerUrl = "https://example.com/torve.deb"))

        assertTrue(phase is UpdateInstallerHandoff.Phase.Failed, "phase=$phase")
        assertTrue("not supported" in phase.reason, "reason=${phase.reason}")
    }

    @Test
    fun sha256MismatchFailsBeforeLaunch() : Unit = runBlocking {
        val payload = "fake-installer-bytes".toByteArray()
        var launched = false
        val handoff = UpdateInstallerHandoff(
            tempDirSupplier = ::tempDir,
            osLauncher = { launched = true },
            urlOpener = { ByteArrayInputStream(payload) },
            osNameSupplier = { "Windows 11" },
        )

        val phase = handoff.start(
            infoWith(
                installerUrl = "https://example.com/torve.exe",
                installerSha256 = "0".repeat(64), // bogus
            ),
        )

        assertTrue(phase is UpdateInstallerHandoff.Phase.Failed, "phase=$phase")
        assertTrue("SHA-256" in phase.reason, "reason=${phase.reason}")
        assertEquals(false, launched, "launcher must NOT run on hash mismatch")
    }

    @Test
    fun happyPathProducesHandedOff() : Unit = runBlocking {
        val payload = "real-installer-bytes-pretend-this-is-an-exe".toByteArray()
        val expectedSha = sha256Hex(payload)
        var launchedFile: File? = null
        val handoff = UpdateInstallerHandoff(
            tempDirSupplier = ::tempDir,
            osLauncher = { launchedFile = it },
            urlOpener = { ByteArrayInputStream(payload) },
            osNameSupplier = { "Windows 11" },
            windowsPostHandoffDelayMillis = 0,
            windowsRelaunchWatchdog = {},
            processTerminator = null,
        )

        val phase = handoff.start(
            infoWith(
                installerUrl = "https://example.com/torve-1.2.3.exe",
                installerSha256 = expectedSha,
            ),
        )

        assertTrue(phase is UpdateInstallerHandoff.Phase.HandedOff, "phase=$phase")
        val launched = assertNotNull(launchedFile, "osLauncher should have been invoked")
        val onDisk = phase.installer
        assertTrue(onDisk.exists(), "installer should still exist on disk after handoff")
        assertEquals(payload.size.toLong(), onDisk.length(), "downloaded size mismatch")
        assertEquals(onDisk.absolutePath, launched.absolutePath, "wrong file passed to launcher")
        assertEquals(payload.size.toLong(), launched.length())

        onDisk.delete()
    }

    @Test
    fun happyPathWithoutHashStillLaunches() : Unit = runBlocking {
        val payload = "no-hash-but-fine".toByteArray()
        var launched = false
        val handoff = UpdateInstallerHandoff(
            tempDirSupplier = ::tempDir,
            osLauncher = { launched = true },
            urlOpener = { ByteArrayInputStream(payload) },
            osNameSupplier = { "Mac OS X" },
        )

        val phase = handoff.start(
            infoWith(
                installerUrl = "https://example.com/torve.dmg",
                installerSha256 = null,
            ),
        )

        assertTrue(phase is UpdateInstallerHandoff.Phase.HandedOff, "phase=$phase")
        assertEquals(true, launched)
        phase.installer.delete()
    }

    @Test
    fun supportsHandoffMatrix() {
        assertTrue(UpdateInstallerHandoff.supportsHandoffOn("Windows 11"))
        assertTrue(UpdateInstallerHandoff.supportsHandoffOn("Mac OS X"))
        assertTrue(UpdateInstallerHandoff.supportsHandoffOn("Darwin"))
        assertEquals(false, UpdateInstallerHandoff.supportsHandoffOn("Linux"))
        assertEquals(false, UpdateInstallerHandoff.supportsHandoffOn(""))
    }

    // ── Fix #6 regression: Windows .msi must use msiexec, not Desktop.open ──
    // Original Desktop.open(file) failed inside Windows Sandbox with
    // "Unsupported URI content" because the Sandbox's .msi file-type
    // handler isn't registered the same way as on a normal install.
    // resolveLauncherCommand picks an explicit msiexec /i command for
    // that case so we don't depend on AWT integration.

    @Test
    fun resolveLauncher_windowsMsi_returnsMsiexecCommand() {
        val file = File("C:/foo/torve-update.msi")
        val cmd = UpdateInstallerHandoff.resolveLauncherCommand(file, "Windows 11")
        // /qb suppresses Restart Manager's "Files in Use" dialog so the
        // baked-in WiX util:CloseApplication custom action can close
        // the running Torve.exe silently. Without /qb msiexec defaults
        // to full UI and the dialog fires before our action runs.
        assertEquals(listOf("msiexec.exe", "/i", file.absolutePath, "/qb"), cmd)
    }

    @Test
    fun resolveLauncher_windowsMsiUppercase_stillMatches() {
        val file = File("C:/foo/torve.MSI")
        val cmd = UpdateInstallerHandoff.resolveLauncherCommand(file, "Windows 11")
        assertEquals("msiexec.exe", cmd?.firstOrNull())
    }

    @Test
    fun resolveLauncher_windowsExe_fallsBackToDesktopOpen() {
        // .exe installers stay on Desktop.open — Windows handles those
        // fine without explicit command resolution.
        val cmd = UpdateInstallerHandoff.resolveLauncherCommand(
            File("C:/foo/torve.exe"),
            "Windows 11",
        )
        assertNull(cmd)
    }

    @Test
    fun resolveLauncher_macOsDmg_fallsBackToDesktopOpen() {
        val cmd = UpdateInstallerHandoff.resolveLauncherCommand(
            File("/tmp/torve.dmg"),
            "Mac OS X",
        )
        assertNull(cmd)
    }

    @Test
    fun resolveLauncher_linuxDeb_fallsBackToDesktopOpen() {
        // Linux is filtered out earlier by supportsHandoffOn, but the
        // resolver shouldn't apply Windows logic if it ever reaches it.
        val cmd = UpdateInstallerHandoff.resolveLauncherCommand(
            File("/tmp/torve.deb"),
            "Linux",
        )
        assertNull(cmd)
    }

    private fun infoWith(
        installerUrl: String? = "https://example.com/torve.exe",
        installerSha256: String? = null,
    ) = UpdateChecker.UpdateInfo(
        tag = "1.2.3",
        name = "Torve 1.2.3",
        htmlUrl = "https://example.com/release/1.2.3",
        publishedAt = null,
        body = null,
        installerUrl = installerUrl,
        installerSha256 = installerSha256,
    )

    private fun tempDir(): File {
        val base = File(System.getProperty("java.io.tmpdir"), "torve-update-handoff-test")
        base.mkdirs()
        return base
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
