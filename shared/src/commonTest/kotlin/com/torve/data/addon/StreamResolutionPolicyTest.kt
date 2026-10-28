package com.torve.data.addon

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamResolutionPolicyTest {
    @Test
    fun torrentioDirectUrlIsAlreadyResolvedByConfiguredDebridAddon() {
        val stream = ParsedStream(
            addonName = "[TB+] Torrentio",
            quality = "1080p",
            title = "Movie",
            directUrl = "https://download.example/movie.mkv",
            addonBaseUrl = "https://torrentio.strem.fun",
        )

        assertTrue(stream.hasPreResolvedAddonPlaybackUrl(userManagedOnly = false))
    }

    @Test
    fun ordinaryHosterUrlStillUsesConfiguredDebridProvider() {
        val stream = ParsedStream(
            addonName = "Hoster addon",
            quality = "1080p",
            title = "Movie",
            directUrl = "https://hoster.example/movie.mkv",
            addonBaseUrl = "https://hoster-addon.example",
        )

        assertFalse(stream.hasPreResolvedAddonPlaybackUrl(userManagedOnly = false))
    }

    @Test
    fun userManagedExtensionCanReturnAReadyCdnUrlWithoutPandaOrLocalDebrid() {
        val stream = ParsedStream(
            addonName = "Independent playback extension", quality = "1080p", title = "Original video",
            directUrl = "https://cdn.example/ready/video.mp4?token=provider-token",
            addonBaseUrl = "https://independent.example/u/configured-manifest",
        )
        assertTrue(stream.hasPreResolvedAddonPlaybackUrl(userManagedOnly = true))
        assertFalse(stream.hasPreResolvedAddonPlaybackUrl(userManagedOnly = false))
    }

    @Test
    fun userManagedExtensionCannotTreatRawNzbOrNonVideoSchemesAsResolvedVideo() {
        val stream = ParsedStream(
            addonName = "Independent extension", quality = "1080p", title = "Original video",
            addonBaseUrl = "https://independent.example",
        )
        for (url in listOf("https://indexer.example/file.NZB?apikey=example", "magnet:?xt=urn:btih:example", "file:///tmp/video.mp4", "data:video/mp4;base64,AAAA")) {
            assertFalse(stream.copy(directUrl = url).hasPreResolvedAddonPlaybackUrl(userManagedOnly = true), url)
        }
        assertFalse(stream.copy(directUrl = "https://cdn.example/video.mp4", addonBaseUrl = null)
            .hasPreResolvedAddonPlaybackUrl(userManagedOnly = true))
    }
}
