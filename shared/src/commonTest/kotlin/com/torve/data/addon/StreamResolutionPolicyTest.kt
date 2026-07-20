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

        assertTrue(stream.hasPreResolvedAddonPlaybackUrl())
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

        assertFalse(stream.hasPreResolvedAddonPlaybackUrl())
    }
}
