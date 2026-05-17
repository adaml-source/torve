package com.torve.data.account

import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaFavoritesRepositoryImplTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun addFavoriteSuccessUpdatesRepositoryStateFromAcknowledgementResponse() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val settings = FakeDeviceLocalSettingsRepository()
        val storage = FakeSecureStorage()
        val httpClient = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath == "/auth/login" -> respondJson(
                    """
                    {
                      "user": {
                        "id": "user-1",
                        "email": "user@example.com",
                        "is_verified": true
                      },
                      "tokens": {
                        "access_token": "access-token",
                        "refresh_token": "refresh-token",
                        "expires_in": 900
                      },
                      "device": {
                        "id": "22222222-2222-2222-2222-222222222222"
                      }
                    }
                    """.trimIndent(),
                )
                request.url.encodedPath == "/me/media-favorites" -> respondJson("""{"favorites": [], "version": 1}""")
                request.url.encodedPath.startsWith("/me/media-favorites/") -> respondJson(
                    """
                    {
                      "ok": true,
                      "version": 2,
                      "updated_at": "2026-05-16T00:02:00Z"
                    }
                    """.trimIndent(),
                )
                request.url.encodedPath == "/me/events" -> respondJson("""{}""", HttpStatusCode.NoContent)
                else -> respondJson("""{}""", HttpStatusCode.NotFound)
            }
        }) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        val authClient = AuthClient(
            localSettingsRepository = settings,
            secureStorage = storage,
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "install-1",
                    device_name = "Test",
                    device_type = "desktop",
                    platform = "desktop_windows",
                )
            },
        )
        val authResult = authClient.login("user@example.com", "password123")
        assertTrue(authResult.success, authResult.error ?: "login failed")
        val api = MediaFavoritesApi(
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
        )
        val repository = MediaFavoritesRepositoryImpl(
            authClient = authClient,
            api = api,
            localSettingsRepository = settings,
            json = json,
        )

        waitUntil { requests.any { it.url.encodedPath == "/me/media-favorites" } }

        repository.addFavorite(
            MediaItem(
                id = "tmdb:movie:123",
                tmdbId = 123,
                type = MediaType.MOVIE,
                title = "Test Movie",
                posterUrl = "https://image.example/poster.jpg",
            ),
        )

        waitUntil {
            repository.state.value.favoriteKeys.contains("movie:123") &&
                repository.state.value.items.singleOrNull()?.title == "Test Movie" &&
                repository.state.value.version == "2"
        }

        val putRequest = requests.last { it.url.encodedPath.startsWith("/me/media-favorites/") }
        val requestBody = parseBody(putRequest)
        assertEquals("movie:123", requestBody["media_key"]?.jsonPrimitive?.content)
        assertEquals("movie", requestBody["media_type"]?.jsonPrimitive?.content)
        assertEquals("Test Movie", requestBody["title"]?.jsonPrimitive?.content)
        assertNull(requestBody["access_token"])
        assertNull(requestBody["refresh_token"])
        assertTrue(repository.state.value.lastError == null)

        repository.clearSessionState()
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!predicate()) {
                    delay(25)
                }
            }
        }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
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

private class FakeSecureStorage(
    private val values: MutableMap<String, String?> = mutableMapOf(),
) : SecureStorage {
    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class FakeDeviceLocalSettingsRepository(
    private val values: MutableMap<String, String?> = mutableMapOf(),
) : DeviceLocalSettingsRepository {
    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
