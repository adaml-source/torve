package com.torve.presentation.tvhome

import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Pins the TV source picker model. Drives the D-pad list ordering,
 * autoplay rule, and explicit fallback chain — so a focus / state
 * regression in the picker UI surfaces as a logic bug here first.
 */
class TvSourcePickerTest {

    private val localFile = PlaybackRoute.LocalFile("/disk/movie.mkv")
    private val lanStream = PlaybackRoute.LanDesktopStream(
        url = "http://192.168.1.10:41122/local/stream/x?token=t",
        headers = mapOf("X-Torve-Lan-Auth" to "shh"),
    )
    private val provider = PlaybackRoute.ProviderStream("https://upstream/movie.mp4")

    @Test
    fun `build orders local first then LAN then provider`() {
        val state = TvSourcePicker.build(
            localFile = localFile,
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.WIFI,
        )
        assertEquals(3, state.options.size)
        assertEquals("Downloaded", state.options[0].label)
        assertEquals(TvSourceTier.BEST, state.options[0].tier)
        assertEquals("On desktop (LAN)", state.options[1].label)
        assertEquals("Provider", state.options[2].label)
    }

    @Test
    fun `build promotes LAN to BEST when no local file`() {
        val state = TvSourcePicker.build(
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.WIFI,
        )
        assertEquals(TvSourceTier.BEST, state.options[0].tier)
        assertEquals("On desktop (LAN)", state.options[0].label)
    }

    @Test
    fun `build suppresses LAN on cellular when wifi-only is true`() {
        val state = TvSourcePicker.build(
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
        )
        assertEquals(1, state.options.size)
        assertEquals("Provider", state.options.single().label)
        assertEquals(TvSourceTier.BEST, state.options.single().tier)
    }

    @Test
    fun `build returns ReDownload when no source is offered`() {
        val state = TvSourcePicker.build(networkMode = NetworkMode.WIFI)
        val sole = state.options.single()
        assertEquals(TvSourceTier.RE_DOWNLOAD, sole.tier)
        assertEquals(false, state.canAutoPlay)
    }

    @Test
    fun `build suppresses LAN when active engine cannot stage headers`() {
        // Prompt 24: an engine without setNextRequestHeaders support
        // (default false) cannot send X-Torve-Lan-Auth, so the desktop
        // hub would 401. The picker must not dangle a doomed LAN row
        // in front of the user.
        val state = TvSourcePicker.build(
            localFile = localFile,
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
            engineSupportsLanHeaders = false,
        )
        // LAN dropped; localFile and provider remain.
        assertEquals(2, state.options.size)
        assertEquals("Downloaded", state.options[0].label)
        assertEquals("Provider", state.options[1].label)
        assertTrue(state.options.none { it.label == "On desktop (LAN)" })
    }

    @Test
    fun `engine-incapable plus cellular guard both suppress LAN independently`() {
        // Either guard alone is enough to drop LAN; both together still
        // produce the same outcome (no double-removal regression).
        val state = TvSourcePicker.build(
            localFile = localFile,
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
            engineSupportsLanHeaders = false,
        )
        assertEquals(2, state.options.size)
        assertTrue(state.options.none { it.label == "On desktop (LAN)" })
    }

    @Test
    fun `engineSupportsLanHeaders defaults to true so existing call sites unchanged`() {
        // Backwards-compat guard: any caller that hasn't yet wired the
        // capability check should keep getting the LAN row, not lose it
        // silently.
        val state = TvSourcePicker.build(
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.WIFI,
        )
        assertEquals("On desktop (LAN)", state.options[0].label)
    }

    @Test
    fun `build retains LAN on cellular when wifi-only is false`() {
        val state = TvSourcePicker.build(
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = false,
        )
        assertEquals(2, state.options.size)
        assertEquals("On desktop (LAN)", state.options[0].label)
    }

    @Test
    fun `autoPlayBest returns the top route when canAutoPlay`() {
        val state = TvSourcePicker.build(
            localFile = localFile,
            lanStream = lanStream,
            networkMode = NetworkMode.WIFI,
        )
        assertTrue(state.canAutoPlay)
        assertEquals(localFile, TvSourcePicker.autoPlayBest(state))
    }

    @Test
    fun `autoPlayBest returns null when only ReDownload is available`() {
        val state = TvSourcePicker.build(networkMode = NetworkMode.WIFI)
        assertNull(TvSourcePicker.autoPlayBest(state))
    }

    @Test
    fun `fallbackAfter returns the next non-redownload option`() {
        val state = TvSourcePicker.build(
            localFile = localFile,
            lanStream = lanStream,
            providerStream = provider,
            networkMode = NetworkMode.WIFI,
        )
        val nextAfterLocal = TvSourcePicker.fallbackAfter(state, localFile)
        assertEquals("On desktop (LAN)", nextAfterLocal?.label)

        val nextAfterLan = TvSourcePicker.fallbackAfter(state, lanStream)
        assertEquals("Provider", nextAfterLan?.label)

        val nextAfterProvider = TvSourcePicker.fallbackAfter(state, provider)
        assertNull(nextAfterProvider, "provider is the last real option — no further fallback")
    }

    @Test
    fun `fallbackAfter returns null when failing route isn't in the picker`() {
        val state = TvSourcePicker.build(localFile = localFile, networkMode = NetworkMode.WIFI)
        val unrelated = PlaybackRoute.LanDesktopStream("http://other/lan/stream")
        assertNull(TvSourcePicker.fallbackAfter(state, unrelated))
    }

    @Test
    fun `provider issue copy travels to the picker state`() {
        val state = TvSourcePicker.build(
            providerStream = provider,
            networkMode = NetworkMode.WIFI,
            providerIssue = "Real-Debrid: 401 unauthorized",
        )
        assertEquals("Real-Debrid: 401 unauthorized", state.providerIssue)
    }

    @Test
    fun `canAutoPlay is false when ReDownload is the only option`() {
        val state = TvSourcePicker.build(networkMode = NetworkMode.WIFI)
        assertFalse(state.canAutoPlay)
    }
}
