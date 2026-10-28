package com.torve.data.addon

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.StreamPreferences
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.*

class UserManagedSourceSearchTest {
    private fun addon(host: String) = InstalledAddon("https://$host/manifest.json",
        AddonManifest(id = host, name = host, version = "1.0.0", resources = listOf("stream")))
    private val body = """{"streams":[{"name":"Ready","title":"Original 1080p","url":"https://video.example/original.mp4"}]}"""

    @Test fun connectedAccountKeepsCachedTorrentSourcesWithoutChangingTheManifest() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val requests = mutableListOf<String>()
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val codec = Json { ignoreUnknownKeys = true }
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
            requests += request.url.toString()
            val response = when (request.url.encodedPath) {
                "/stream/movie/tt123.json" -> """{"streams":[{"name":"Torrentio","title":"Original 1080p","infoHash":"$hash"}]}"""
                "/api/cache/check" -> """{"status":"success","response":[true]}"""
                "/api/transfer/directdl" -> """{"status":"success","content":[{"path":"original.mp4","link":"https://video.example/original.mp4"}]}"""
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(codec) } }
        try {
            val aggregator = StreamAggregator(StremioAddonClient(http, codec), DebridClient(http, codec), StreamScorer())
            val manifests = listOf(addon("ready.example"))
            // This reproduces the old Watch path: dropping the saved account hides every raw result.
            assertTrue(aggregator.resolveStreams(manifests, MediaType.MOVIE, "tt123",
                preferences = StreamPreferences(cachedOnly = true)).isEmpty())
            val streams = aggregator.resolveStreams(manifests, MediaType.MOVIE, "tt123",
                debridAccounts = mapOf(DebridServiceType.PREMIUMIZE to "private-test-key"),
                preferences = StreamPreferences(cachedOnly = true))
            assertEquals(1, streams.size, "Requests: $requests; provider health: ${aggregator.getAddonHealth()}")
            assertEquals(hash, streams.single().infoHash)
            assertTrue(streams.single().isCached)
            val providerRequests = requests.filter { it.startsWith("https://ready.example/") }
            assertEquals(2, providerRequests.size)
            assertTrue(providerRequests.all { it == "https://ready.example/stream/movie/tt123.json" })
            assertTrue(requests.any { "/api/cache/check" in it })
        } finally { http.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun slowProviderDoesNotLoseReadySourcesOrRetryForever() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                calls += request.url.host + request.url.encodedPath
                if (request.url.host == "slow.example") awaitCancellation()
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }))
        try {
            val json = Json { ignoreUnknownKeys = true }
            val aggregator = StreamAggregator(StremioAddonClient(http, json), DebridClient(http, json), StreamScorer())
            val result = aggregator.resolveStreams(listOf(addon("ready.example"), addon("slow.example")),
                MediaType.SERIES, "tt123", season = 2, episode = 3)
            assertEquals("https://video.example/original.mp4", result.single().directUrl)
            assertEquals(20_000L, testScheduler.currentTime)
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.endsWith("/stream/series/tt123:2:3.json") })
            assertEquals(1, aggregator.getAddonHealth().size)
        } finally { http.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun closingSearchCancelsNetworkWorkWithoutRetrying() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        var calls = 0
        var completed = false
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { calls++; awaitCancellation() }
        }))
        try {
            val json = Json { ignoreUnknownKeys = true }
            val aggregator = StreamAggregator(StremioAddonClient(http, json), DebridClient(http, json), StreamScorer())
            val job = launch {
                aggregator.resolveStreams(listOf(addon("slow.example")), MediaType.MOVIE, "tt123")
                completed = true
            }
            runCurrent(); job.cancel(); advanceUntilIdle()
            assertFalse(completed)
            assertEquals(1, calls)
        } finally { http.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun existingPlatformPolicyStillRetriesTransientFailure() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = false
        var calls = 0
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls++
                if (calls == 1) error("Fixture provider temporarily offline")
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }))
        try {
            val json = Json { ignoreUnknownKeys = true }
            val aggregator = StreamAggregator(StremioAddonClient(http, json), DebridClient(http, json), StreamScorer())
            assertEquals(1, aggregator.resolveStreams(listOf(addon("ready.example")), MediaType.MOVIE, "tt123").size)
            assertEquals(2, calls)
            assertEquals(3_000L, testScheduler.currentTime)
        } finally { http.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }
}
