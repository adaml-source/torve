package com.torve.presentation.detail

import com.torve.data.metadata.*
import com.torve.data.trakt.TraktClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ContentCommunityTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun movieFactsDecodeAllFieldsAndDoNotInventZeroFinancials() {
        val movie = json.decodeFromString<TmdbMovie>("""{"id":1,"original_title":"호프","original_language":"ko","tagline":"Hope","status":"Released","release_date":"2026-10-08","budget":46000000,"revenue":41212538,"production_companies":[{"id":2,"name":"Studio"}],"production_countries":[{"iso_3166_1":"KR","name":"South Korea"}]}""")
        val facts = movieFacts(movie).associate { it.label to it.value }
        assertEquals("호프", facts["Original title"])
        assertEquals("KO", facts["Original language"])
        assertEquals("South Korea", facts["Origin country"])
        assertEquals("46000000", facts["Budget (USD)"])
        assertEquals("41212538", facts["Revenue (USD)"])
        val absent = movieFacts(TmdbMovie(id = 1, budget = 0, revenue = -1))
        assertTrue(absent.isEmpty())
    }
    @Test fun showFactsUseOriginalNameFirstAirDateAndNetworks() {
        val facts = tvFacts(TmdbTv(id = 2, originalName = "Original", firstAirDate = "2026-10-20",
            networks = listOf(TmdbCompany(3, "Network")), originCountry = listOf("US"))).associate { it.label to it.value }
        assertEquals("2026-10-20", facts["First aired"])
        assertEquals("Network", facts["Networks"])
        assertEquals("US", facts["Origin country"])
        assertFalse(facts.containsKey("Budget (USD)"))
    }
    @Test fun commentsArePublicPagedAndPreserveSpoilerMetadata() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("2", request.headers["trakt-api-version"])
            assertEquals("10", request.url.parameters["limit"])
            requests += request.url.encodedPath
            respond("""[{"id":17,"comment":"A [spoiler]secret[/spoiler]","spoiler":false,"likes":3,"created_at":"2026-10-20T00:00:00Z","user_rating":9,"user":{"username":"viewer"}}]""",
                HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json"), "X-Pagination-Page-Count" to listOf("2")))
        })
        try {
            val client = TraktClient(http, json)
            val first = client.getContentComments("tt123", false, 1)
            assertTrue(first.hasMore)
            assertTrue(first.items.single().containsSpoilers)
            assertEquals("viewer", first.items.single().author)
            assertEquals("https://trakt.tv/comments/17", first.items.single().permalink)
            val last = client.getContentComments("tt124", true, 2)
            assertFalse(last.hasMore)
            assertEquals(listOf("/movies/tt123/comments/newest", "/shows/tt124/comments/newest"), requests)
        } finally { http.close() }
    }
    @Test fun unknownIdentityNeverSendsAnUnrelatedCommentsRequest() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; error("No usable identity") })
        try {
            val service = ContentCommunity(TmdbApiClient(http), TraktClient(http, json))
            val item = com.torve.domain.model.MediaItem(id = "opaque", title = "Provider title", type = com.torve.domain.model.MediaType.MOVIE)
            assertTrue(service.comments(item, 1).items.isEmpty())
            assertEquals(0, calls)
        } finally { http.close() }
    }
    @Test fun extensionOnlyCommentsUseTheConnectedUsersTokenAndNeverMetadataFallback() = runTest {
        val old = com.torve.data.addon.ExtensionRuntimePolicy.userMetadataOnly
        com.torve.data.addon.ExtensionRuntimePolicy.userMetadataOnly = true
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            assertEquals("api.trakt.tv", request.url.host)
            assertEquals("/movies/tt4034228/comments/newest", request.url.encodedPath)
            assertEquals("Bearer connected-test-token", request.headers[HttpHeaders.Authorization])
            respond("""[{"id":17,"comment":"A connected account review","user":{"username":"viewer"}}]""", HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json"), "X-Pagination-Page-Count" to listOf("1")))
        })
        try {
            val tokens = com.torve.data.trakt.auth.TraktTokenStore(MemorySecrets(), json)
            val client = TraktClient(http,json)
            val authorized = com.torve.data.trakt.api.TraktAuthorizedApi(client,tokens)
            val service = ContentCommunity(TmdbApiClient(http), client, authorized)
            val item = com.torve.domain.model.MediaItem("tt4034228",type=com.torve.domain.model.MediaType.MOVIE,title="Manchester by the Sea")
            assertTrue(service.comments(item,1).items.isEmpty())
            assertEquals(0,calls, "No automatic public Trakt request when disconnected")
            tokens.write(com.torve.data.trakt.TraktTokens("connected-test-token","refresh",3600,kotlinx.datetime.Clock.System.now().epochSeconds))
            assertEquals("viewer", service.comments(item,1).items.single().author)
            assertEquals(1,calls)
            assertTrue(service.comments(item.copy(id="334541",tmdbId=334541),1).items.isEmpty())
            assertEquals(1,calls, "No native ID lookup in extension-only mode")
            tokens.clear()
            assertTrue(service.comments(item,1).items.isEmpty())
            assertEquals(1,calls, "Disconnect takes effect immediately")
        } finally { http.close(); com.torve.data.addon.ExtensionRuntimePolicy.userMetadataOnly = old }
    }
    private class MemorySecrets : com.torve.domain.integrations.IntegrationSecretStore {
        private val entries = mutableMapOf<Pair<com.torve.domain.integrations.IntegrationSecretKey,String?>,String>()
        override suspend fun put(key:com.torve.domain.integrations.IntegrationSecretKey,value:String,subKey:String?) { entries[key to subKey]=value }
        override suspend fun get(key:com.torve.domain.integrations.IntegrationSecretKey,subKey:String?)=entries[key to subKey]
        override suspend fun remove(key:com.torve.domain.integrations.IntegrationSecretKey,subKey:String?) { entries.remove(key to subKey) }
        override suspend fun setStorageMode(key:com.torve.domain.integrations.IntegrationSecretKey,mode:com.torve.domain.integrations.IntegrationStorageMode) {}
        override suspend fun getStorageMode(key:com.torve.domain.integrations.IntegrationSecretKey)=com.torve.domain.integrations.IntegrationStorageMode.DEVICE_ONLY
        override suspend fun clearAllSecrets() { entries.clear() }
    }

}
