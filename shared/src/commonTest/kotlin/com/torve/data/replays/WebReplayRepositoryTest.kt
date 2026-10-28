package com.torve.data.replays

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebReplayRepositoryTest {
    private fun TestScope.mockClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = UnconfinedTestDispatcher(testScheduler)
        addHandler(handler)
    }))
    @Test fun followsAnIntermediatePageThenOkWithoutDownloadingVideo() = runTest {
        val seen = mutableListOf<String>()
        val client = mockClient { request ->
            seen += request.url.toString()
            respond(when (request.url.host) {
                "middle.example" -> """<iframe src="https://ok.ru/videoembed/123"></iframe>"""
                "ok.ru" -> """<div data-options="{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;{\&quot;hlsManifestUrl\&quot;:\&quot;https://cdn.example/v.m3u8\&quot;}&quot;}}"></div>"""
                else -> error("Media must not be downloaded by the resolver")
            })
        }
        val repo = WebReplayRepository(client, UnconfinedTestDispatcher(testScheduler))
        try { assertEquals("https://cdn.example/v.m3u8", repo.resolve(ReplaySourceOption("1", "OK", "https://middle.example/event", "https://sports.example/game")).url)
            assertEquals(2, seen.size)
        } finally { repo.close(); client.close() }
    }
    @Test fun rejectsRedirectsToPrivateNetwork() = runTest {
        val client = mockClient { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1/secret")) }
        val repo = WebReplayRepository(client, UnconfinedTestDispatcher(testScheduler))
        try { assertFailsWith<IllegalStateException> { repo.resolve(ReplaySourceOption("1", "host", "https://host.example/", "https://sports.example/")) } }
        finally { repo.close(); client.close() }
    }
    @Test fun blockedWebsitesProduceAnActionableError() = runTest {
        val client = mockClient { respond("blocked", HttpStatusCode.Forbidden) }
        val repo = WebReplayRepository(client, UnconfinedTestDispatcher(testScheduler))
        try { val error = assertFailsWith<IllegalStateException> { repo.catalogue(ReplayWebsite("1", "Sports", "https://sports.example/")) }
            assertTrue(error.message.orEmpty().contains("browser verification")) }
        finally { repo.close(); client.close() }
    }
    @Test fun directStreamsNeedNoMetadataRequest() = runTest {
        val client = mockClient { error("No metadata request expected") }
        val repo = WebReplayRepository(client, UnconfinedTestDispatcher(testScheduler))
        try { assertEquals("https://cdn.example/v.mp4?token=short", repo.resolve(ReplaySourceOption("1", "Video", "https://cdn.example/v.mp4?token=short", "https://sports.example/game")).url) }
        finally { repo.close(); client.close() }
    }
    @Test fun boundsIntermediatePageLoops() = runTest {
        var calls = 0
        val client = mockClient { calls++; respond("""<iframe src="https://host.example/loop"></iframe>""") }
        val repo = WebReplayRepository(client, UnconfinedTestDispatcher(testScheduler))
        try { assertFailsWith<IllegalStateException> { repo.resolve(ReplaySourceOption("1", "host", "https://host.example/loop", "https://sports.example/game")) }; assertEquals(1, calls) }
        finally { repo.close(); client.close() }
    }
    @Test fun boundsPageResponseBytes() = runTest {
        val client = mockClient { respond("x".repeat(2_000_001)) }
        val repo = WebReplayRepository(client, UnconfinedTestDispatcher(testScheduler))
        try { assertFailsWith<IllegalStateException> { repo.catalogue(ReplayWebsite("1", "Sports", "https://sports.example/")) } }
        finally { repo.close(); client.close() }
    }
}
