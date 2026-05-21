package com.torve.data.trakt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TraktClientWatchlistContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun addMovieWatchlistUsesTraktWatchlistEndpointAndMovieIds() = runTest {
        var captured: HttpRequestData? = null
        val client = client { request ->
            captured = request
            respondJson("""{"added":{"movies":1},"not_found":{"movies":[]}}""")
        }

        client.addToWatchlist(
            accessToken = "access-token",
            body = TraktWatchlistBody(
                movies = listOf(
                    TraktHistoryMovie(
                        ids = TraktIds(tmdb = 123, imdb = "tt0000123"),
                    ),
                ),
            ),
        )

        val request = assertNotNull(captured)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/sync/watchlist", request.url.encodedPath)
        assertEquals("Bearer access-token", request.headers["Authorization"])
        val body = parseBody(request)
        val ids = body["movies"]!!.jsonArray.single().jsonObject["ids"]!!.jsonObject
        assertEquals("123", ids["tmdb"]?.jsonPrimitive?.content)
        assertEquals("tt0000123", ids["imdb"]?.jsonPrimitive?.content)
        assertFalse(requestBodyContains(request, "title"))
    }

    @Test
    fun addShowWatchlistUsesShowPayload() = runTest {
        var captured: HttpRequestData? = null
        val client = client { request ->
            captured = request
            respondJson("""{"added":{"shows":1},"not_found":{"shows":[]}}""")
        }

        client.addToWatchlist(
            accessToken = "access-token",
            body = TraktWatchlistBody(
                shows = listOf(
                    TraktHistoryShow(
                        ids = TraktIds(tmdb = 456, imdb = "tt0000456"),
                    ),
                ),
            ),
        )

        val request = assertNotNull(captured)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/sync/watchlist", request.url.encodedPath)
        val body = parseBody(request)
        val ids = body["shows"]!!.jsonArray.single().jsonObject["ids"]!!.jsonObject
        assertEquals("456", ids["tmdb"]?.jsonPrimitive?.content)
        assertEquals("tt0000456", ids["imdb"]?.jsonPrimitive?.content)
        assertTrue(body["movies"] == null || body["movies"].toString() == "null")
        assertFalse(requestBodyContains(request, "title"))
    }

    @Test
    fun removeMovieWatchlistUsesRemoveEndpointAndMovieIds() = runTest {
        var captured: HttpRequestData? = null
        val client = client { request ->
            captured = request
            respondJson("""{"deleted":{"movies":1},"not_found":{"movies":[]}}""")
        }

        client.removeFromWatchlist(
            accessToken = "access-token",
            body = TraktWatchlistBody(
                movies = listOf(
                    TraktHistoryMovie(
                        ids = TraktIds(tmdb = 123, imdb = "tt0000123"),
                    ),
                ),
            ),
        )

        val request = assertNotNull(captured)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/sync/watchlist/remove", request.url.encodedPath)
        val body = parseBody(request)
        val ids = body["movies"]!!.jsonArray.single().jsonObject["ids"]!!.jsonObject
        assertEquals("123", ids["tmdb"]?.jsonPrimitive?.content)
        assertEquals("tt0000123", ids["imdb"]?.jsonPrimitive?.content)
    }

    @Test
    fun watchlistFailureDoesNotExposeTraktResponseBody() = runTest {
        val client = client { _ ->
            respondJson(
                """{"error":"secret upstream body","access_token":"raw-token","refresh_token":"raw-refresh"}""",
                status = HttpStatusCode.BadRequest,
            )
        }

        val error = assertFailsWith<Exception> {
            client.addToWatchlist(
                accessToken = "access-token",
                body = TraktWatchlistBody(
                    movies = listOf(
                        TraktHistoryMovie(
                            ids = TraktIds(tmdb = 123, imdb = "tt0000123"),
                        ),
                    ),
                ),
            )
        }

        val message = error.message.orEmpty()
        assertEquals("Trakt request failed (HTTP 400).", message)
        assertFalse(message.contains("secret upstream body"))
        assertFalse(message.contains("raw-token"))
        assertFalse(message.contains("raw-refresh"))
    }

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): TraktClient {
        val httpClient = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return TraktClient(httpClient, json)
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private suspend fun parseBody(request: HttpRequestData) = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readBytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            body.writeTo(channel)
            channel.close()
            channel.readRemaining().readBytes().decodeToString()
        }
        else -> error("Unsupported body type: ${body::class}")
    }.let { json.parseToJsonElement(it).jsonObject }

    private suspend fun requestBodyContains(request: HttpRequestData, value: String): Boolean =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readBytes().decodeToString()
            is OutgoingContent.WriteChannelContent -> {
                val channel = ByteChannel(autoFlush = true)
                body.writeTo(channel)
                channel.close()
                channel.readRemaining().readBytes().decodeToString()
            }
            else -> error("Unsupported body type: ${body::class}")
        }.contains(value)
}
