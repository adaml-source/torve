package com.torve.desktop.playback

import com.torve.desktop.mpv.MpvRuntimeLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContains

/**
 * Pins the MPV Labs Settings UX contract: copy strings, selectable
 * engine list, reset notice, and effective mode after preference
 * normalization. The Settings UI consumes this snapshot directly, so
 * keeping the helper covered keeps the surface honest.
 */
class MpvLabsStatusTest {

    private fun discovery(found: Boolean, paths: List<String> = emptyList()): MpvRuntimeLocator.DiscoveryResult =
        MpvRuntimeLocator.DiscoveryResult(
            found = found,
            mpvDirectory = if (found) "/fake/mpv" else null,
            discoverySource = if (found) "test" else null,
            attemptedPaths = paths,
            diagnosticMessage = if (found) "found" else "libmpv not found",
        )

    @Test
    fun `saved VLC stays VLC regardless of discovery`() {
        val snap = MpvLabsStatus.compute(discovery(found = false), DesktopPlayerMode.VLC)
        assertEquals(DesktopPlayerMode.VLC, snap.effectiveMode)
        assertEquals(false, snap.wasResetFromMpv)
        // No reset notice when the user never picked MPV.
        assertNull(snap.resetNotice)
    }

    @Test
    fun `saved MPV with libmpv missing normalizes to VLC and surfaces a reset notice`() {
        val snap = MpvLabsStatus.compute(discovery(found = false), DesktopPlayerMode.MPV)
        assertEquals(DesktopPlayerMode.VLC, snap.effectiveMode)
        assertTrue(snap.wasResetFromMpv)
        assertNotNull(snap.resetNotice)
        // Reset copy must explicitly explain WHY the rail is on VLC.
        assertContains(snap.resetNotice!!, "MPV Labs is not available")
    }

    @Test
    fun `saved MPV with libmpv present keeps MPV`() {
        val snap = MpvLabsStatus.compute(discovery(found = true), DesktopPlayerMode.MPV)
        assertEquals(DesktopPlayerMode.MPV, snap.effectiveMode)
        assertEquals(false, snap.wasResetFromMpv)
        assertNull(snap.resetNotice)
    }

    @Test
    fun `selectableModes drops MPV when libmpv missing`() {
        val snap = MpvLabsStatus.compute(discovery(found = false), DesktopPlayerMode.VLC)
        assertEquals(listOf(DesktopPlayerMode.VLC), snap.selectableModes)
    }

    @Test
    fun `selectableModes includes both engines when libmpv present`() {
        val snap = MpvLabsStatus.compute(discovery(found = true), DesktopPlayerMode.VLC)
        assertEquals(DesktopPlayerMode.entries.toList(), snap.selectableModes)
    }

    @Test
    fun `state label is Unavailable on this device when libmpv missing`() {
        val snap = MpvLabsStatus.compute(discovery(found = false), DesktopPlayerMode.VLC)
        assertEquals(MpvLabsStatus.State.UNAVAILABLE, snap.state)
        assertEquals("Unavailable on this device", snap.stateLabel)
    }

    @Test
    fun `state label is Available when libmpv found`() {
        val snap = MpvLabsStatus.compute(discovery(found = true), DesktopPlayerMode.VLC)
        assertEquals(MpvLabsStatus.State.AVAILABLE, snap.state)
        assertEquals("Available", snap.stateLabel)
    }

    @Test
    fun `description avoids scary playback-time wording when unavailable`() {
        val snap = MpvLabsStatus.compute(discovery(found = false), DesktopPlayerMode.VLC)
        // Premium copy: VLC framed as the recommended choice, not as
        // a fallback. Avoid "missing" / "failed" / "warning" in the
        // primary description.
        assertContains(snap.description, "VLC is active and recommended")
        assertContains(snap.description, "MPV Labs requires libmpv")
        // Negative-tone audit:
        listOf("missing", "failed", "warning", "broken").forEach { word ->
            assertTrue(!snap.description.contains(word), "description must not contain '$word': ${snap.description}")
        }
    }

    @Test
    fun `setup guide body lists every attempted discovery path`() {
        val paths = listOf(
            "/Users/test/runtime/mpv",
            "C:\\torve\\mpv",
            "/usr/lib/x86_64-linux-gnu/libmpv.so.2",
        )
        val snap = MpvLabsStatus.compute(discovery(found = false, paths = paths), DesktopPlayerMode.VLC)
        val body = MpvLabsStatus.setupGuideBody(snap)
        paths.forEach { p ->
            assertContains(body, p)
        }
        // Honest copy: optional + VLC recommended.
        assertContains(body, "optional")
        assertContains(body, "VLC is the recommended choice")
        // Power-user override hooks must be discoverable.
        assertContains(body, "torve.desktop.mpv.path")
        assertContains(body, "TORVE_MPV_PATH")
    }

    @Test
    fun `engine status row reads quiet and positive when default VLC ready and MPV unavailable`() {
        // Prompt 18 acceptance: the default player path feels premium
        // and quiet. A normal user with libmpv missing should see
        // "Default player ready" first; the advanced-engine suffix
        // names the unavailable state but never reads as alarm.
        val snap = MpvLabsStatus.compute(discovery(found = false), DesktopPlayerMode.VLC)
        val row = MpvLabsStatus.engineStatusRow(snap)
        assertEquals("Default player ready. Advanced MPV unavailable on this device.", row)
        // Negative-tone audit: must not call this a fallback or warn.
        listOf("fallback", "warning", "missing", "failed", "broken", "error").forEach { word ->
            assertTrue(!row.contains(word), "engine status row must not contain '$word': $row")
        }
    }

    @Test
    fun `engine status row says Advanced MPV available when libmpv discovered`() {
        val snap = MpvLabsStatus.compute(discovery(found = true), DesktopPlayerMode.VLC)
        val row = MpvLabsStatus.engineStatusRow(snap)
        assertEquals("Default player ready. Advanced MPV available.", row)
    }

    @Test
    fun `engine status row drops the advanced suffix when MPV is the active engine`() {
        // No "Advanced unavailable" tail to add when Advanced IS what
        // the user has selected — the row would read confusingly
        // ("Default player ready (Advanced MPV active). Advanced MPV
        // available.") otherwise.
        val snap = MpvLabsStatus.compute(discovery(found = true), DesktopPlayerMode.MPV)
        val row = MpvLabsStatus.engineStatusRow(snap)
        assertEquals("Default player ready (Advanced MPV active).", row)
    }

    @Test
    fun `setup guide body still renders when no paths recorded`() {
        val snap = MpvLabsStatus.compute(discovery(found = false, paths = emptyList()), DesktopPlayerMode.VLC)
        val body = MpvLabsStatus.setupGuideBody(snap)
        // Doesn't crash, body is non-empty, and tells the user what
        // happens after a re-check.
        assertTrue(body.isNotBlank())
        assertContains(body, "no paths recorded")
    }
}
