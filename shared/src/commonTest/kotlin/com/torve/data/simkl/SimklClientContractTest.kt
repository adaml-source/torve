package com.torve.data.simkl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SimklClientContractTest {
    private fun body(request: HttpRequestData) = Json.parseToJsonElement(
        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    @Test fun animeReadsUseMappedSeasonsAndTheLastActivityTimestamp() = runTest {
        var captured: HttpRequestData? = null
        val http = HttpClient(MockEngine { request -> captured = request; respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        SimklClient(http).getLibrary("test-token", "anime", "2026-10-06T15:20:00Z")
        val request = captured!!
        assertEquals("full_anime_seasons", request.url.parameters["extended"])
        assertEquals("2026-10-06T15:20:00Z", request.url.parameters["date_from"])
        assertEquals("yes", request.url.parameters["episode_watched_at"])
        assertEquals("yes", request.url.parameters["include_all_episodes"])
        assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
        assertTrue(request.headers[HttpHeaders.UserAgent]!!.startsWith("Torve/"))
        http.close()
    }

    @Test fun wholeSeriesWritesHaveAnExplicitCompletionStatusButEpisodeWritesDoNot() = runTest {
        val sent = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request -> sent += body(request); respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json() }
        }
        val client = SimklClient(http)
        client.addToHistory("test-token", SimklSyncBody(shows = listOf(SimklSyncItem(SimklIds(tmdb = 20)))))
        client.addToHistory("test-token", simklEpisodeBody(SimklIds(tmdb = 20), 2, 3))
        assertEquals("completed", sent.first().getValue("shows").jsonArray.single().jsonObject["status"]?.jsonPrimitive?.content)
        assertNull(sent.last().getValue("shows").jsonArray.single().jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        http.close()
    }

    @Test fun removingAPlannedTitleUsesTheSupportedEndpointAndPreservesAlreadyWatchedTitles() = runTest {
        val removals = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) { removals += request; respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            else respond("""{"movies":[{"status":"plantowatch","movie":{"ids":{"simkl":1,"tmdb":10}}},{"status":"plantowatch","last_watched_at":"2026-10-06T20:00:00Z","movie":{"ids":{"simkl":2,"tmdb":20}}}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) } }
        SimklClient(http).removeFromWatchlist("test-token", SimklSyncBody(movies = listOf(
            SimklSyncItem(SimklIds(tmdb = 10)), SimklSyncItem(SimklIds(tmdb = 20)))))
        assertEquals("/sync/history/remove", removals.single().url.encodedPath)
        assertEquals(10, body(removals.single()).getValue("movies").jsonArray.single().jsonObject.getValue("ids").jsonObject.getValue("tmdb").jsonPrimitive.int)
        http.close()
    }

    @Test fun unrecognizedMediaDoesNotDisappearFromThePendingSyncQueueAsASuccess() = runTest {
        val http = HttpClient(MockEngine { respond("""{"not_found":{"shows":[{"ids":{"tmdb":20}}]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json() }
        }
        assertFailsWith<IllegalStateException> { SimklClient(http).addToHistory("test-token", simklEpisodeBody(SimklIds(tmdb = 20), 1, 1)) }
        http.close()
    }
}
