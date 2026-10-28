package com.torve.data.usenet

import com.torve.data.addon.isUsenetStream
import com.torve.data.panda.NzbIndexerRow
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ConfiguredUsenetSourcesTest {
    private val rows = listOf(NzbIndexerRow("custom", "https://first.example", "first-key"),
        NzbIndexerRow("custom", "https://videothek.example/api", "second-key"))
    private fun config(indexers: List<NzbIndexerRow> = rows, owner: String = "alice") =
        UsenetSourceConfiguration(owner, true, indexers, "torbox", "torbox-key")
    private fun rss(title: String = "Movie.2026.1080p.WEB-DL", id: String = "one") =
        """<rss><channel><item><title>$title</title><guid>$id</guid><link>/details/$id</link><enclosure url="/api?t=get&amp;id=$id" length="1000000"/></item></channel></rss>"""

    @Test fun bothIndexersAreQueriedWithoutAnExtensionEvenWhenTheFirstHasResults() = runTest {
        val requests = mutableListOf<Url>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            requests += request.url
            assertEquals(NewznabClient.USER_AGENT, request.headers[HttpHeaders.UserAgent])
            assertEquals("movie", request.url.parameters["t"])
            assertEquals("1234567", request.url.parameters["imdbid"])
            assertEquals(if (request.url.host == "first.example") "first-key" else "second-key", request.url.parameters["apikey"])
            respond(rss())
        } }))
        try {
            val provider = ConfiguredUsenetSources(NewznabClient(client), TorBoxUsenetClient(client), { config() }, { "alice" })
            val result = provider.search(MediaType.MOVIE, "tt1234567", null, null, StreamPreferences())
            assertEquals(setOf("first.example", "videothek.example"), requests.map { it.host }.toSet())
            assertTrue(requests.all { it.encodedPath == "/api" }, "Never append /api twice")
            assertEquals(2, result.streams.size, "Source errors: ${result.sourceErrors}")
            assertTrue(result.streams.all { it.isUsenetStream() && it.configuredNzb != null && !it.isCached })
            assertTrue(result.streams.all { Url(it.directUrl!!).parameters["apikey"]?.isNotBlank() == true })
        } finally { client.close() }
    }

    @Test fun deniedFirstIndexerDoesNotHideTheSecondOrLeakItsKey() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            if (request.url.host == "first.example") respond("denied", HttpStatusCode.Forbidden) else respond(rss())
        } }))
        try {
            val provider = ConfiguredUsenetSources(NewznabClient(client), TorBoxUsenetClient(client), { config() }, { "alice" })
            val result = provider.search(MediaType.MOVIE, "tt1234567", null, null, StreamPreferences())
            assertEquals(1, result.streams.size)
            assertEquals("videothek.example", result.streams.single().source)
            assertTrue(result.providerStats.single { it.provider == "first.example" }.failed)
            assertTrue(result.sourceErrors.single().contains("allowed tools"))
            assertFalse(result.sourceErrors.joinToString().contains("first-key"))
        } finally { client.close() }
    }

    @Test fun timeoutIsIsolatedAndProfileChangesCancelTheLookup() = runTest {
        var owner = "alice"
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.host == "first.example") awaitCancellation()
                respond(rss())
            }
        }))
        try {
            val provider = ConfiguredUsenetSources(NewznabClient(client), TorBoxUsenetClient(client), { config() }, { owner })
            val result = provider.search(MediaType.MOVIE, "tt1234567", null, null, StreamPreferences())
            assertEquals(1, result.streams.size)
            assertEquals(12_000L, testScheduler.currentTime)
            owner = "bob"
            assertFailsWith<IllegalStateException> { provider.resolve(result.streams.single()) }
            assertFailsWith<CancellationException> {
                provider.search(MediaType.MOVIE, "tt1234567", null, null, StreamPreferences())
            }
        } finally { client.close() }
    }

    @Test fun episodeLookupRejectsWrongEpisodesAndUploadsOnlyTheSelectedNzb() = runTest {
        var uploads = 0
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            calls += request.url.host + request.url.encodedPath
            when {
                request.url.host == "videothek.example" && request.url.parameters["t"] == "tvsearch" -> {
                    assertEquals("2", request.url.parameters["season"])
                    assertEquals("3", request.url.parameters["ep"])
                    respond(rss("Show.S02E03.1080p") + rss("Show.S02E04.1080p", "wrong"))
                }
                request.url.host == "videothek.example" -> {
                    assertEquals("get", request.url.parameters["t"])
                    assertEquals(NewznabClient.USER_AGENT, request.headers[HttpHeaders.UserAgent])
                    respond("<?xml version=\"1.0\"?><nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\"><file/></nzb>")
                }
                request.url.encodedPath.endsWith("createusenetdownload") -> {
                    uploads++
                    assertEquals("Bearer torbox-key", request.headers[HttpHeaders.Authorization])
                    respond("""{"success":true,"data":{"usenetdownload_id":42}}""")
                }
                request.url.encodedPath.endsWith("mylist") -> respond("""{"data":[{"download_state":"completed","progress":100,"files":[{"id":1,"name":"Show.S02E04.mkv","size":9000},{"id":2,"name":"Show.S02E03.mkv","size":1000}]}]}""")
                request.url.encodedPath.endsWith("requestdl") -> {
                    assertEquals("2", request.url.parameters["file_id"])
                    respond("""{"data":{"data":"https://cdn.example/episode.mkv"}}""")
                }
                else -> error("Unexpected request")
            }
        } }))
        try {
            val provider = ConfiguredUsenetSources(NewznabClient(client), TorBoxUsenetClient(client), { config(rows.takeLast(1)) }, { "alice" })
            val result = provider.search(MediaType.SERIES, "tt1234567", 2, 3, StreamPreferences())
            assertEquals(1, result.streams.size, "Source errors: ${result.sourceErrors}")
            assertEquals(0, uploads, "Browsing must never start downloads")
            assertEquals("https://cdn.example/episode.mkv", provider.resolve(result.streams.single()).url)
            assertEquals(1, uploads)
        } finally { client.close() }
    }

    @Test fun xmlPermissionErrorIsNeverUploadedAsAnNzb() = runTest {
        val hosts = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            hosts += request.url.host
            respond("""<error code="100" description="Tool not approved"/>""")
        })
        try {
            val result = TorBoxUsenetClient(client).resolve("https://videothek.example/api?t=get", "torbox-key") {}
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Tool not approved") == true)
            assertEquals(listOf("videothek.example"), hosts)
        } finally { client.close() }
    }

    @Test fun profileChangeWhileFetchingNzbStopsBeforeTorBoxUpload() = runTest {
        var owner = "alice"
        var uploads = 0
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                when {
                    request.url.parameters["t"] == "movie" -> respond(rss())
                    request.url.host == "first.example" -> {
                        owner = "bob"
                        respond("<nzb><file/></nzb>")
                    }
                    else -> { uploads++; error("Must not upload after a profile change") }
                }
            }
        }))
        try {
            val provider = ConfiguredUsenetSources(NewznabClient(client), TorBoxUsenetClient(client),
                { config(rows.take(1)) }, { owner })
            val stream = provider.search(MediaType.MOVIE, "tt1234567", null, null, StreamPreferences()).streams.single()
            assertFailsWith<CancellationException> { provider.resolve(stream) }
            assertEquals(0, uploads)
        } finally { client.close() }
    }
}
