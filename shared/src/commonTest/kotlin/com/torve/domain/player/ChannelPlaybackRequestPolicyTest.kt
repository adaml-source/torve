package com.torve.domain.player

import com.torve.domain.model.Channel
import kotlin.test.*

class ChannelPlaybackRequestPolicyTest {
    @Test fun inlineHeadersOverrideKodiAndVlcOptionsWithoutBecomingPartOfTheSourceURL() {
        val request = ChannelPlaybackRequestPolicy.request(Channel("News", "https://fixture.example/live.m3u8|User-Agent=Inline%20Client&Cookie=session%3Dfixture",
            userAgent = "Attribute", vlcOptions = listOf("http-user-agent=VLC", "http-referrer=https://origin.example/"),
            kodiProps = mapOf("inputstream.adaptive.stream_headers" to "User-Agent=Kodi&Origin=https%3A%2F%2Forigin.example")))
        assertEquals("https://fixture.example/live.m3u8", request.url)
        assertEquals("Inline Client", request.headers["User-Agent"])
        assertEquals("session=fixture", request.headers["Cookie"])
        assertEquals("https://origin.example/", request.headers["Referer"])
        assertEquals("https://origin.example", request.headers["Origin"])
    }
}
