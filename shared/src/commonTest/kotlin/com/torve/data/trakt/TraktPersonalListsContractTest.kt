package com.torve.data.trakt

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

class TraktPersonalListsContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun createRenameAndDeleteUseCorrectMethodsAndRenamePreservesListProperties() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond(if (request.method == HttpMethod.Delete) "" else """{"name":"Cinema","ids":{"trakt":7},"privacy":"public"}""",
                if (request.method == HttpMethod.Delete) HttpStatusCode.NoContent else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        val client = TraktClient(http, json)
        client.createPersonalList("test-token", " Cinema ")
        client.renamePersonalList("test-token", 7, " Renamed ")
        client.deletePersonalList("test-token", 7)
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete), requests.map { it.method })
        assertEquals(listOf("/users/me/lists", "/users/me/lists/7", "/users/me/lists/7"), requests.map { it.url.encodedPath })
        val renamed = json.parseToJsonElement((requests[1].body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
        assertEquals(setOf("name"), renamed.keys)
        assertEquals("Renamed", renamed["name"]?.jsonPrimitive?.content)
        http.close()
    }
    @Test fun paginationLoadsEveryPageAndRejectedTitleDoesNotReportSuccess() = runTest {
        var pages = 0
        val http = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                pages++
                respond("""[{"type":"movie","movie":{"title":"Film $pages","ids":{"tmdb":$pages}}}]""",
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Pagination-Page-Count" to listOf("2")))
            } else respond("""{"not_found":{"movies":[{"ids":{"tmdb":8}}]}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        val client = TraktClient(http, json)
        assertEquals(2, client.getPersonalListItems("test-token", 7).size)
        assertEquals(2, pages)
        assertFailsWith<IllegalStateException> { client.mutatePersonalListItems("test-token", 7,
            TraktListItemsWrite(movies = listOf(TraktHistoryMovie(TraktIds(tmdb = 8)))), false) }
        http.close()
    }
}
