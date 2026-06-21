package com.torve.data.account

import com.torve.domain.model.MediaFavorite
import com.torve.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MediaFavoritesApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun upsertFavoriteOmitsInvalidSourceDeviceId() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson(
                """
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "media_key": "movie:123",
                  "media_type": "movie",
                  "tmdb_id": 123,
                  "title": "Test Movie",
                  "added_at": "2026-05-16T00:00:00Z",
                  "updated_at": "2026-05-16T00:00:00Z"
                }
                """.trimIndent(),
            )
        }

        api.upsertFavorite(
            accessToken = "token",
            favorite = MediaFavorite(
                mediaKey = "movie:123",
                mediaType = MediaType.MOVIE,
                tmdbId = 123,
                title = "Test Movie",
            ),
            sourceDeviceId = "desktop-installation-id",
        )

        val request = captured ?: error("request not captured")
        val body = parseBody(request)
        assertEquals("/me/media-favorites/movie:123", request.url.encodedPath)
        assertEquals("movie:123", body["media_key"]?.jsonPrimitive?.content)
        assertFalse("source_device_id" in body)
    }

    @Test
    fun upsertFavoriteSendsUuidSourceDeviceId() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson(
                """
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "media_key": "movie:123",
                  "media_type": "movie",
                  "tmdb_id": 123,
                  "title": "Test Movie",
                  "added_at": "2026-05-16T00:00:00Z",
                  "updated_at": "2026-05-16T00:00:00Z"
                }
                """.trimIndent(),
            )
        }

        api.upsertFavorite(
            accessToken = "token",
            favorite = MediaFavorite(
                mediaKey = "movie:123",
                mediaType = MediaType.MOVIE,
                tmdbId = 123,
                title = "Test Movie",
            ),
            sourceDeviceId = "22222222-2222-2222-2222-222222222222",
        )

        val body = parseBody(captured ?: error("request not captured"))
        assertEquals("22222222-2222-2222-2222-222222222222", body["source_device_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun upsertFavoritePrefersWrappedFavoriteResponseWithOpaqueStringVersion() = runTest {
        val api = api { _ ->
            respondJson(
                """
                {
                  "favorite": {
                    "id": "11111111-1111-1111-1111-111111111111",
                    "media_key": "movie:123",
                    "media_type": "movie",
                    "tmdb_id": 123,
                    "title": "Test Movie",
                    "updated_at": "2026-05-16T00:00:00Z"
                  },
                  "version": "2026-05-16T00:01:00Z",
                  "updated_at": "2026-05-16T00:01:00Z"
                }
                """.trimIndent(),
            )
        }

        val result = api.upsertFavorite(
            accessToken = "token",
            favorite = MediaFavorite(
                mediaKey = "movie:123",
                mediaType = MediaType.MOVIE,
                tmdbId = 123,
                title = "Test Movie",
            ),
            sourceDeviceId = null,
        )

        assertEquals("movie:123", result.favorite.mediaKey)
        assertEquals("Test Movie", result.favorite.title)
        assertEquals("2026-05-16T00:01:00Z", result.version)
        assertEquals("2026-05-16T00:01:00Z", result.updatedAt)
    }

    @Test
    fun upsertFavoriteAcceptsAcknowledgementResponseWithoutFullFavorite() = runTest {
        var captured: HttpRequestData? = null
        val api = api { request ->
            captured = request
            respondJson(
                """
                {
                  "ok": true,
                  "version": 15,
                  "updated_at": "2026-05-16T00:02:00Z"
                }
                """.trimIndent(),
            )
        }

        val result = api.upsertFavorite(
            accessToken = "token",
            favorite = MediaFavorite(
                mediaKey = "movie:123",
                mediaType = MediaType.MOVIE,
                tmdbId = 123,
                title = "Test Movie",
            ),
            sourceDeviceId = null,
        )

        assertEquals("movie:123", result.favorite.mediaKey)
        assertEquals("movie", result.favorite.mediaType)
        assertEquals("Test Movie", result.favorite.title)
        assertEquals("15", result.version)
        assertEquals("2026-05-16T00:02:00Z", result.updatedAt)
        val body = parseBody(captured ?: error("request not captured"))
        assertEquals("movie:123", body["media_key"]?.jsonPrimitive?.content)
        assertEquals("movie", body["media_type"]?.jsonPrimitive?.content)
        assertEquals("Test Movie", body["title"]?.jsonPrimitive?.content)
        assertNull(body["access_token"])
        assertNull(body["refresh_token"])
    }

    @Test
    fun listFavoritesParsesBackendFavoritesOpaqueStringVersionAndUpdatedAt() = runTest {
        val api = api { _ ->
            respondJson(
                """
                {
                  "favorites": [
                    {
                      "id": "11111111-1111-1111-1111-111111111111",
                      "media_key": "movie:123",
                      "media_type": "movie",
                      "tmdb_id": 123,
                      "title": "Test Movie",
                      "updated_at": "2026-05-16T00:00:00Z"
                    }
                  ],
                  "version": "2026-05-16T00:01:00Z",
                  "updated_at": "2026-05-16T00:01:00Z"
                }
                """.trimIndent(),
            )
        }

        val dto = api.listFavorites("token")

        assertEquals("2026-05-16T00:01:00Z", dto.version)
        assertEquals("2026-05-16T00:01:00Z", dto.updatedAt)
        assertEquals("movie:123", dto.favorites.single().mediaKey)
    }

    @Test
    fun listFavoritesParsesLegacyItemsField() = runTest {
        val api = api { _ ->
            respondJson(
                """
                {
                  "items": [
                    {
                      "id": "11111111-1111-1111-1111-111111111111",
                      "media_key": "movie:321",
                      "media_type": "movie",
                      "tmdb_id": 321,
                      "title": "Legacy Movie",
                      "updated_at": "2026-05-16T00:00:00Z"
                    }
                  ],
                  "updated_at": "2026-05-16T00:01:00Z"
                }
                """.trimIndent(),
            )
        }

        val dto = api.listFavorites("token")

        assertEquals("movie:321", dto.favoriteRows.single().mediaKey)
        assertEquals("2026-05-16T00:01:00Z", dto.updatedAt)
    }

    @Test
    fun authenticatedFavoritesCallsIncludeInstallationHeader() = runTest {
        var captured: HttpRequestData? = null
        val api = api(installationId = "install-abc") { request ->
            captured = request
            respondJson("""{"favorites":[]}""")
        }

        api.listFavorites("token")

        assertEquals("install-abc", captured?.headers?.get("X-Torve-Installation-Id"))
    }

    private fun api(
        installationId: String? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MediaFavoritesApi {
        val client = HttpClient(MockEngine { request -> handler(request) }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return MediaFavoritesApi(
            client,
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { installationId },
        )
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
}
