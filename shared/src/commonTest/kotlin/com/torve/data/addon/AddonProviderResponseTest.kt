package com.torve.data.addon

import com.torve.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class AddonProviderResponseTest {
    @Test fun informationalLinksAndProviderErrorClipsAreNeverOfferedAsMovies() = runBlocking {
        val hash = "d".repeat(40)
        val http = HttpClient(MockEngine {
            respond("""{"streams":[
                {"title":"Film","url":"https://cdn.example/videos/film.mp4"},
                {"title":"Torrent","infoHash":"$hash"},
                {"name":"Provider timeout","externalUrl":"https://github.com/provider/help"},
                {"title":"TorBox is currently unavailable!","url":"https://torrentio.strem.fun/videos/torbox_error.mp4"},
                {"title":"Unavailable","url":"https://aiostreams.elfhosted.com/static/error.mp4"}
            ]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val partials = mutableListOf<List<ParsedStream>>()
            val result = StremioAddonClient(http, Json).getStreams("https://provider.example", MediaType.MOVIE, "tt4034228") {
                partials += it
            }
            assertEquals(listOf("Film", "Torrent"), result.map { it.title })
            assertTrue(partials.all { rows -> rows.all { it.title in setOf("Film", "Torrent") } })
            assertEquals("https://cdn.example/videos/film.mp4", result.first().directUrl)
        } finally { http.close() }
    }

    @Test fun aSuccessfulProviderWithNoSourcesRemainsAnEmptyResult() = runBlocking {
        val http = HttpClient(MockEngine { respond("""{"streams":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try { assertTrue(StremioAddonClient(http, Json).getStreams("https://provider.example", MediaType.MOVIE, "tt3553976").isEmpty()) }
        finally { http.close() }
    }
}
