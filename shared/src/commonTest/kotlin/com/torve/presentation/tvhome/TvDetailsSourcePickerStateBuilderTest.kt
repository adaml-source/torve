package com.torve.presentation.tvhome

import com.torve.domain.lanlibrary.NetworkMode
import com.torve.domain.lanlibrary.PlaybackRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Pins the TvDetailsScreen source-picker state mapping (Prompt 11C).
 *
 * The mapper folds (localFilePath, lanRoute, providerAvailable,
 * networkMode, wifiOnlyForLan) into a TvSourcePickerState honoring the
 * locked priority. The provider entry is a synthetic sentinel so the
 * picker can route a "Provider" selection back into the existing
 * fetchStreams() flow without needing a real URL up front.
 */
class TvDetailsSourcePickerStateBuilderTest {

    private val lan = PlaybackRoute.LanDesktopStream(
        url = "http://192.168.1.10:41122/local/stream/abc?token=t",
        headers = mapOf("X-Torve-Lan-Auth" to "shh"),
    )

    @Test
    fun `local file plus LAN plus provider yields three options in priority order`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = "/storage/dl/m.mkv",
            lanRoute = lan,
            providerAvailable = true,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(3, state.options.size)
        assertEquals("Downloaded", state.options[0].label)
        assertEquals(TvSourceTier.BEST, state.options[0].tier)
        assertEquals("On desktop (LAN)", state.options[1].label)
        assertEquals("Provider", state.options[2].label)
        // The provider entry is the synthetic sentinel — the detail
        // screen recognises it via [isProviderFetchSentinel].
        assertTrue(TvDetailsSourcePickerStateBuilder.isProviderFetchSentinel(state.options[2]))
    }

    @Test
    fun `LAN promoted to BEST when no local file`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = null,
            lanRoute = lan,
            providerAvailable = true,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(2, state.options.size)
        assertEquals("On desktop (LAN)", state.options[0].label)
        assertEquals(TvSourceTier.BEST, state.options[0].tier)
    }

    @Test
    fun `no provider when providerAvailable is false`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = "/storage/dl/m.mkv",
            lanRoute = null,
            providerAvailable = false,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        // Only the local-file row should be present — a missing-debrid
        // env shouldn't surface a dead-end Provider fallback.
        assertEquals(1, state.options.size)
        assertEquals("Downloaded", state.options.single().label)
    }

    @Test
    fun `cellular guard suppresses LAN`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = null,
            lanRoute = lan,
            providerAvailable = true,
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = true,
        )
        // LAN dropped — only Provider remains.
        assertEquals(1, state.options.size)
        assertEquals("Provider", state.options.single().label)
    }

    @Test
    fun `cellular plus wifiOnly false retains LAN`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = null,
            lanRoute = lan,
            providerAvailable = true,
            networkMode = NetworkMode.CELLULAR,
            wifiOnlyForLan = false,
        )
        assertEquals(2, state.options.size)
        assertEquals("On desktop (LAN)", state.options[0].label)
    }

    @Test
    fun `blank local-file path is treated as missing`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = "   ",
            lanRoute = lan,
            providerAvailable = true,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        // Local file dropped, LAN promoted to BEST.
        assertEquals("On desktop (LAN)", state.options[0].label)
        assertEquals(TvSourceTier.BEST, state.options[0].tier)
    }

    @Test
    fun `nothing playable still emits a ReDownload option`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = null,
            lanRoute = null,
            providerAvailable = false,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        assertEquals(1, state.options.size)
        assertEquals(TvSourceTier.RE_DOWNLOAD, state.options.single().tier)
        assertFalse(state.canAutoPlay)
    }

    @Test
    fun `provider issue copy travels through the mapper`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = "/storage/dl/m.mkv",
            lanRoute = null,
            providerAvailable = true,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
            providerIssue = "Real-Debrid: 401 unauthorized",
        )
        assertEquals("Real-Debrid: 401 unauthorized", state.providerIssue)
    }

    @Test
    fun `LAN selection carries the original headers through`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = null,
            lanRoute = lan,
            providerAvailable = true,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        val lanOption = state.options.first { it.route is PlaybackRoute.LanDesktopStream }
        val laneRoute = lanOption.route as PlaybackRoute.LanDesktopStream
        // Same URL + same headers as the input route — the picker is
        // a pure mapper, never strips auth.
        assertEquals(lan.url, laneRoute.url)
        assertEquals("shh", laneRoute.headers["X-Torve-Lan-Auth"])
    }

    @Test
    fun `fallback chain skips the synthetic provider sentinel cleanly`() {
        val state = TvDetailsSourcePickerStateBuilder.build(
            localFilePath = "/storage/dl/m.mkv",
            lanRoute = lan,
            providerAvailable = true,
            networkMode = NetworkMode.WIFI,
            wifiOnlyForLan = true,
        )
        // Failure of LocalFile falls back to LAN.
        val nextAfterLocal = TvSourcePicker.fallbackAfter(
            state,
            PlaybackRoute.LocalFile("/storage/dl/m.mkv"),
        )
        assertNotNull(nextAfterLocal)
        assertEquals("On desktop (LAN)", nextAfterLocal.label)
        // Failure of LAN falls back to Provider (synthetic).
        val nextAfterLan = TvSourcePicker.fallbackAfter(state, lan)
        assertNotNull(nextAfterLan)
        assertTrue(TvDetailsSourcePickerStateBuilder.isProviderFetchSentinel(nextAfterLan))
    }

    @Test
    fun `isProviderFetchSentinel rejects a real provider URL`() {
        val opt = TvSourcePickerOption(
            label = "Provider",
            hint = "",
            route = PlaybackRoute.ProviderStream("https://upstream/movie.mp4"),
            tier = TvSourceTier.BEST,
        )
        assertFalse(TvDetailsSourcePickerStateBuilder.isProviderFetchSentinel(opt))
    }
}
