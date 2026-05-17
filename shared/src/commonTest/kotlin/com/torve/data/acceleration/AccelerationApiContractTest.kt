package com.torve.data.acceleration

import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.model.MediaType
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.content.OutgoingContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AccelerationApiContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun outcomeCallsIncludeStableInstallationIdHeader() = runTest {
        var capturedHeader: String? = null
        val api = AccelerationApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    capturedHeader = request.headers["X-Torve-Installation-Id"]
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(json) }
            },
            authClient = authClient(),
            json = json,
            baseUrlProvider = { "https://api.torve.app" },
        )

        val posted = api.reportOutcome(
            AccelerationOutcomeDto(
                contentId = "tmdb:movie:1",
                providerType = "real_debrid",
                sourceKey = "https://provider.example/opaque-source-key",
                success = true,
            ),
        )

        assertTrue(posted)
        assertEquals("install-accel-1", capturedHeader)
    }

    @Test
    fun startupCandidatesParseMemoryId() = runTest {
        val envelope = parseStartupAccelerationResponse(
            json = json,
            raw = """{"candidates":[{"memory_id":"mem-123","source_key":"source-1","provider_type":"real_debrid","title":"Movie","quality":"1080p"}]}""",
        )

        assertEquals("mem-123", envelope.candidates.single().memoryId)
    }

    @Test
    fun accelerationSourcesParseMemoryId() = runTest {
        var capturedPath = ""
        val api = AccelerationApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    capturedPath = request.url.encodedPath
                    respond(
                        content = """{"candidates":[{"memory_id":"mem-source","source_key":"source-1","title":"Movie"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(json) }
            },
            authClient = authClient(),
            json = json,
            baseUrlProvider = { "https://api.torve.app" },
        )

        val candidates = api.getSourceCandidates(
            SourceAccelerationRequest(
                mediaType = MediaType.MOVIE,
                imdbId = "tt1234567",
                contentId = "tmdb:movie:1",
            ),
        )

        assertEquals("/me/acceleration/sources", capturedPath)
        assertEquals("mem-source", candidates.single().memoryId)
    }

    @Test
    fun streamHandoffIncludesInstallationIdAndMemoryReferenceBody() = runTest {
        var capturedHeader: String? = null
        var capturedBody = ""
        val api = AccelerationApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    capturedHeader = request.headers["X-Torve-Installation-Id"]
                    capturedBody = parseBody(request)
                    respond(
                        content = """{"url":"https://api.torve.app/handoff/stream-1","is_direct":false,"supports_range":true,"stream_id":"stream-1","expires_in_seconds":300}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(json) }
            },
            authClient = authClient(),
            json = json,
            baseUrlProvider = { "https://api.torve.app" },
        )

        val response = api.requestStreamHandoff(
            contentId = "tmdb:movie:1",
            memoryId = "mem-123",
        )

        assertEquals("install-accel-1", capturedHeader)
        assertTrue(capturedBody.contains(""""content_id":"tmdb:movie:1""""))
        assertTrue(capturedBody.contains(""""memory_id":"mem-123""""))
        assertFalse(capturedBody.contains("integrityToken"))
        assertEquals("https://api.torve.app/handoff/stream-1", response.url)
        assertEquals(false, response.isDirect)
        assertEquals(300, response.expiresInSeconds)
    }

    @Test
    fun streamHandoffErrorsUseStableCodeAndDoNotRetainRawBody() = runTest {
        val rawSecret = "https://provider.example/movie.mp4?token=secret"
        val api = AccelerationApi(
            httpClient = HttpClient(
                MockEngine {
                    respond(
                        content = """{"detail":{"error_code":"stream_reference_not_found","message":"$rawSecret"}}""",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(json) }
            },
            authClient = authClient(),
            json = json,
            baseUrlProvider = { "https://api.torve.app" },
        )

        try {
            api.requestStreamHandoff(contentId = "tmdb:movie:1", memoryId = "missing")
            fail("Expected StreamHandoffApiException")
        } catch (error: StreamHandoffApiException) {
            assertEquals("stream_reference_not_found", error.errorCode)
            assertFalse(error.message.orEmpty().contains(rawSecret))
            assertFalse(error.body.contains(rawSecret))
            assertTrue(error.body.contains("code=stream_reference_not_found"))
        }
    }

    private fun authClient(): AuthClient {
        return AuthClient(
            localSettingsRepository = MapSettings(
                mutableMapOf(
                    AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                    AuthClient.KEY_AUTH_USER_ID to "user-1",
                    AuthClient.KEY_AUTH_IS_VERIFIED to "true",
                ),
            ),
            secureStorage = MapSecureStorage(
                mutableMapOf(
                    AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
                    AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
                    "auth_token_expires_at" to "4102444800000",
                ),
            ),
            httpClient = HttpClient(MockEngine { error("Unexpected auth request ${it.url}") }),
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "install-accel-1",
                    device_name = "Test",
                    device_type = "desktop",
                    platform = "desktop",
                )
            },
        )
    }

    private suspend fun parseBody(request: HttpRequestData): String = when (val body = request.body) {
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readBytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            body.writeTo(channel)
            channel.close()
            channel.readRemaining().readBytes().decodeToString()
        }
        else -> body.toString()
    }

    private class MapSettings(
        private val values: MutableMap<String, String>,
    ) : DeviceLocalSettingsRepository {
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private class MapSecureStorage(
        private val values: MutableMap<String, String>,
    ) : SecureStorage {
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun putString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}
