package com.torve.data.addon

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StreamProviderDeadlineTest {
    private fun installed(host: String) = InstalledAddon("https://$host/manifest.json",
        AddonManifest(id = host, name = host, version = "1", resources = listOf("stream")))

    @Test fun stalledRawTorrentCacheDoesNotHoldBackAnotherProvidersReadyVideo() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val hash = "c".repeat(40)
        val client = HttpClient(MockEngine) { engine {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { request ->
                val body = when (request.url.host) {
                    "raw.example" -> """{"streams":[{"title":"Raw","infoHash":"$hash"}]}"""
                    "ready.example" -> {
                        delay(500)
                        """{"streams":[{"title":"Ready","url":"https://cdn.example/movie.mp4"}]}"""
                    }
                    else -> { delay(100_000); "{}" }
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        } }
        try {
            var firstReady: Long? = null
            val aggregator = StreamAggregator(StremioAddonClient(client, Json), DebridClient(client, Json), StreamScorer())
            val result = aggregator.resolveStreams(listOf(installed("raw.example"), installed("ready.example")), MediaType.MOVIE, "tt4034228",
                debridAccounts = mapOf(DebridServiceType.TORBOX to "fixture"), preferences = StreamPreferences(cachedOnly = true),
                onPartialStreams = { if (it.any { row -> row.directUrl != null } && firstReady == null) firstReady = testScheduler.currentTime })
            assertEquals(500, firstReady)
            assertEquals("https://cdn.example/movie.mp4", result.single().directUrl)
        } finally { client.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun searchingRawTorrentsChecksCacheOnceAndNeverResolvesOrCreatesDownloads() = runBlocking {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val hash = "a".repeat(40)
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = Dispatchers.Unconfined
            addHandler { request ->
                requests += request.url.encodedPath
                assertEquals(HttpMethod.Get, request.method, "Source search cannot create downloads")
                val body = when {
                    request.url.host == "raw.example" -> """{"streams":[{"title":"Film 1080p","infoHash":"$hash"}]}"""
                    request.url.encodedPath.endsWith("/checkcached") -> """{"success":true,"data":{"$hash":{"hash":"$hash","size":1000000000}}}"""
                    else -> error("Source search must not resolve a torrent: ${request.url.encodedPath}")
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        } }
        try {
            val aggregator = StreamAggregator(StremioAddonClient(client, Json), DebridClient(client, Json), StreamScorer())
            val result = aggregator.resolveStreams(listOf(installed("raw.example")), MediaType.MOVIE, "tt4034228",
                debridAccounts = mapOf(DebridServiceType.TORBOX to "fixture"), preferences = StreamPreferences(cachedOnly = true),
                onPartialStreams = {})
            assertEquals(1, result.size, "Observed requests: $requests")
            assertTrue(result.single().isCached)
            assertEquals(2, requests.size)
        } finally { client.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun stalledCacheCheckCannotBlockReadyDirectSourcesForAWholeSearchDeadline() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val hash = "b".repeat(40)
        val client = HttpClient(MockEngine) { engine {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.host == "api.torbox.app") delay(100_000)
                respond("""{"streams":[{"title":"Ready","url":"https://cdn.example/movie.mp4"},{"title":"Raw","infoHash":"$hash"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        } }
        try {
            val aggregator = StreamAggregator(StremioAddonClient(client, Json), DebridClient(client, Json), StreamScorer())
            val result = aggregator.resolveStreams(listOf(installed("fast.example")), MediaType.MOVIE, "tt4034228",
                debridAccounts = mapOf(DebridServiceType.TORBOX to "fixture"), preferences = StreamPreferences(cachedOnly = true),
                onPartialStreams = {})
            assertEquals("https://cdn.example/movie.mp4", result.single().directUrl)
            assertTrue(testScheduler.currentTime <= 5_000, "Cache stalls must not restart during final publication")
        } finally { client.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun processingAnImmediateProviderResponseDoesNotUseItsNetworkDeadline() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val client = HttpClient(MockEngine) { engine {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler {
            respond("""{"streams":[{"title":"Fixture","url":"https://video.example/fixture.mp4"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        } }
        try {
            val aggregator = StreamAggregator(StremioAddonClient(client, Json), DebridClient(client, Json), StreamScorer())
            var stats = emptyList<ProviderSourceStat>()
            var published = 0
            val result = aggregator.resolveStreams(listOf(installed("fast.example")), MediaType.MOVIE, "tt4034228",
                onStatistics = { stats = it }, onPartialStreams = {
                    delay(25_000) // Local verification/publication, not the provider request.
                    published += it.size
                })
            assertEquals(1, result.size, aggregator.getAddonHealth().toString())
            assertEquals(1, published)
            assertFalse(stats.single().failed)
            assertEquals(25_000, testScheduler.currentTime)
        } finally { client.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun aSlowProviderDoesNotDelayPublicationFromTheFastOne() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val client = HttpClient(MockEngine) { engine {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { request ->
            if (request.url.host == "slow.example") delay(100_000)
            respond("""{"streams":[{"title":"Fixture","url":"https://video.example/fixture.mp4"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        } }
        try {
            val aggregator = StreamAggregator(StremioAddonClient(client, Json), DebridClient(client, Json), StreamScorer())
            val publicationTimes = mutableListOf<Long>()
            var stats = emptyList<ProviderSourceStat>()
            val result = aggregator.resolveStreams(listOf(installed("fast.example"), installed("slow.example")),
                MediaType.MOVIE, "tt3553976", onStatistics = { stats = it },
                onPartialStreams = { publicationTimes += testScheduler.currentTime })
            assertEquals(1, result.size, aggregator.getAddonHealth().toString())
            assertEquals(listOf(0L), publicationTimes)
            assertEquals(20_000, testScheduler.currentTime)
            assertFalse(stats.single { it.provider == "fast.example" }.failed)
            assertTrue(stats.single { it.provider == "slow.example" }.failed)
        } finally { client.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }
}
