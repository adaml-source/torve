package com.torve.data.addon

import com.torve.data.debrid.DebridClient
import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class ProviderSourceStatisticsTest {
    @Test fun httpFailureIsNotReportedAsAHealthyEmptyProvider() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val http = HttpClient(MockEngine { respond("{\"error\":\"unavailable\"}", HttpStatusCode.ServiceUnavailable,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            var stats = emptyList<ProviderSourceStat>()
            val addon = InstalledAddon("https://failed.example/manifest.json", AddonManifest("failed", "Unavailable provider", "1", resources = listOf("stream")))
            val aggregator = StreamAggregator(StremioAddonClient(http, json), DebridClient(http, json), StreamScorer())
            assertTrue(aggregator.resolveStreams(listOf(addon), MediaType.MOVIE, "tt123", onStatistics = { stats = it }).isEmpty())
            assertTrue(stats.single().failed)
            assertEquals(0, stats.single().found)
        } finally { http.close() }
    }
    @Test fun eachLookupOwnsItsStatsAndConfiguredSecretsAreNeverDisplayed() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val http = HttpClient(MockEngine { request ->
            val body = if (request.url.host == "empty.example") """{"streams":[]}"""
                else """{"streams":[{"name":"Provider","title":"A","url":"https://video.example/a.mp4"},{"name":"Provider","title":"B","url":"https://video.example/b.mp4"}]}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val aggregator = StreamAggregator(StremioAddonClient(http, json), DebridClient(http, json), StreamScorer())
            val addons = listOf(
                InstalledAddon("https://first.example/u/private-token/manifest.json", AddonManifest("first", "First provider", "1", resources = listOf("stream"))),
                InstalledAddon("https://empty.example/manifest.json", AddonManifest("empty", "Empty provider", "1", resources = listOf("stream"))))
            var rows = emptyList<ProviderSourceStat>()
            val streams = aggregator.resolveStreams(addons, MediaType.MOVIE, "tt123", onStatistics = { rows = it })
            assertEquals(2, streams.size, "rows=$rows health=${aggregator.getAddonHealth()}")
            assertEquals(listOf("First provider", "Empty provider"), rows.map { it.provider })
            assertEquals(listOf(2, 0), rows.map { it.found })
            assertTrue(rows.all { !it.failed && it.responseMs >= 0 })
            assertFalse(rows.toString().contains("private-token"))
            aggregator.resolveStreams(emptyList(), MediaType.MOVIE, "tt456", onStatistics = { rows = it })
            assertTrue(rows.isEmpty())
        } finally { http.close() }
    }
}
