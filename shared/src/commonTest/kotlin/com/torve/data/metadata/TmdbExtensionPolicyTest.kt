package com.torve.data.metadata

import com.torve.data.addon.ExtensionRuntimePolicy
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TmdbExtensionPolicyTest {
    @Test fun catalogueAndEnrichmentAreIndependent() {
        val discovery = listOf("/search/multi", "/discover/movie", "/trending/tv/week", "/movie/popular", "/person/popular")
        val details = listOf("/movie/123", "/tv/123/season/1", "/find/tt1234567", "/movie/123/recommendations", "/person/42", "/list/501", "/movie/123/lists")
        for (endpoint in discovery + details) assertFalse(TmdbExtensionSelection().permits(endpoint))
        for (endpoint in discovery) assertFalse(TmdbExtensionSelection(enrichment = true).permits(endpoint), endpoint)
        for (endpoint in details) assertTrue(TmdbExtensionSelection(enrichment = true).permits(endpoint), endpoint)
        for (endpoint in discovery + details) assertTrue(TmdbExtensionSelection(catalogue = true).permits(endpoint))
    }

    @Test fun newProfilesAndImplicitLegacyDefaultsRequireExplicitConsent() = runTest {
        val previous = ExtensionRuntimePolicy.optionalMetadataExtensions
        ExtensionRuntimePolicy.optionalMetadataExtensions = true
        val rows = mutableMapOf<String, String>()
        val prefs = object : PreferencesRepository {
            override suspend fun getString(key: String) = rows[key]
            override suspend fun setString(key: String, value: String) { rows[key] = value }
            override suspend fun remove(key: String) { rows.remove(key) }
        }
        try {
            assertEquals(TmdbExtensionSelection(), prefs.tmdbExtensionSelection())
            rows[KEY_TMDB_CATALOGUE_ENABLED] = "false"
            assertEquals(TmdbExtensionSelection(), prefs.tmdbExtensionSelection())
            rows[KEY_TMDB_CATALOGUE_ENABLED] = "true"
            assertEquals(TmdbExtensionSelection(catalogue = true), prefs.tmdbExtensionSelection())
            rows[KEY_TMDB_CATALOGUE_ENABLED] = "TRUE"
            assertFalse(prefs.tmdbExtensionSelection().catalogue)
            rows[KEY_TMDB_ENRICHMENT_ENABLED] = "true"
            assertEquals(TmdbExtensionSelection(enrichment = true), prefs.tmdbExtensionSelection())
            rows.clear() // The repository switches to a different profile's rows.
            assertEquals(TmdbExtensionSelection(), prefs.tmdbExtensionSelection())
            ExtensionRuntimePolicy.optionalMetadataExtensions = false
            assertEquals(TmdbExtensionSelection(true, true), prefs.tmdbExtensionSelection())
        } finally { ExtensionRuntimePolicy.optionalMetadataExtensions = previous }
    }

    @Test fun optedOutClientDoesNotResolveDnsOrSendHttpAndCanBeEnabledWithoutRestart() = runTest {
        var selection = TmdbExtensionSelection()
        var requests = 0
        var dns = 0
        val http = HttpClient(MockEngine { requests++; respond("""{"page":1,"results":[],"total_pages":1,"total_results":0}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            val api = TmdbApiClient(http, apiKeyProvider = { "fixture" }, resolvedHostProvider = { dns++; null },
                requestAllowed = { selection.permits(it) })
            assertFailsWith<TmdbExtensionDisabledException> { api.searchMulti("fixture") }
            assertFailsWith<TmdbExtensionDisabledException> { api.getMovieDetail(123) }
            assertFailsWith<TmdbExtensionDisabledException> { api.getWatchProviderLogosFromBackend("https://fixture.invalid", "movie", "US") }
            assertEquals(0, requests); assertEquals(0, dns)
            selection = TmdbExtensionSelection(catalogue = true)
            api.searchMulti("fixture")
            assertEquals(1, requests)
            selection = TmdbExtensionSelection(enrichment = true)
            assertFailsWith<TmdbExtensionDisabledException> { api.searchMulti("fixture") }
            assertEquals(1, requests)
        } finally { http.close() }
    }

    @Test fun disabledEnrichmentDoesNotFetchOrReturnAnEarlierCachedOverlay() = runTest {
        var enabled = false
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            assertEquals("images,credits", request.url.parameters["append_to_response"])
            respond("""{"id":123,"title":"Fixture","credits":{"cast":[{"id":7,"name":"Performer"}]}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val repo = LocalizedMediaRepository(TmdbApiClient(http, { "fixture" }, { null }), backgroundScope,
                enrichmentAllowed = { enabled })
            assertNull(repo.presentation("123", "movie", "en", includeCast = true))
            assertEquals(0, requests)
            enabled = true
            assertEquals("Performer", repo.presentation("123", "movie", "en", includeCast = true)!!.cast.single().name)
            enabled = false
            assertNull(repo.presentation("123", "movie", "en", includeCast = true))
            assertEquals(1, requests)
        } finally { http.close() }
    }
}
