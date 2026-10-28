package com.torve.data.replays

import com.torve.data.network.HttpClientFactory
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Explicit opt-in metadata probe. It never requests the resolved media bytes. */
class WebReplayLiveProbeTest {
    @Test fun userWebsiteCatalogueAndOkHostResolve() = runBlocking {
        assumeTrue("Live network probe is opt-in", System.getenv("TORVE_REPLAY_LIVE") == "1")
        val client = HttpClientFactory.create(); val repository = WebReplayRepository(client)
        try {
            val site = ReplayWebsite("probe", "User website", "https://basketball-video.com/")
            val catalogue = repository.catalogue(site); assertTrue(catalogue.events.isNotEmpty())
            val options = repository.sources(catalogue.events.first()); assertTrue(options.isNotEmpty())
            val option = options.firstOrNull { it.title.contains("OK", true) } ?: error("No OK source on the current first event")
            val playback = repository.resolve(option); assertTrue(Url(playback.url).protocol.name in setOf("http", "https"))
            println("Live metadata verified: ${catalogue.events.size} events, ${options.size} host choices, resolved host ${Url(playback.url).host}. No media bytes downloaded.")
        } finally { repository.close(); client.close() }
    }
}
