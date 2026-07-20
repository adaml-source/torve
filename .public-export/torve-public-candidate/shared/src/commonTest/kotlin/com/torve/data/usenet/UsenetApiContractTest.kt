package com.torve.data.usenet

import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class UsenetApiContractTest {

    @Test
    fun resolverCallsIncludeInstallationIdHeader() = runTest {
        var capturedHeader: String? = null
        val api = api(
            engine = MockEngine { request ->
                capturedHeader = request.headers["X-Torve-Installation-Id"]
                respondJson("""{"state":"warming","job_id":"job-1"}""")
            },
        )

        api.resolve(
            contentId = "tmdb:movie:1",
            candidate = UsenetCandidateDto(candidateId = "cand-1", hashKey = "hash-1"),
        )

        assertEquals("install-usenet-1", capturedHeader)
    }

    @Test
    fun resolverErrorsUseStableCodeAndDoNotRetainRawBody() = runTest {
        val rawSecret = "https://provider.example/tokenized-playback-url?source_key=secret"
        val api = api(
            engine = MockEngine {
                respondJson(
                    body = """{"detail":{"code":"device_required","message":"$rawSecret"}}""",
                    status = HttpStatusCode.Forbidden,
                )
            },
        )

        try {
            api.resolve(
                contentId = "tmdb:movie:1",
                candidate = UsenetCandidateDto(candidateId = "cand-1", hashKey = "hash-1"),
            )
            fail("Expected UsenetApiException")
        } catch (error: UsenetApiException) {
            assertEquals("device_required", error.errorCode)
            assertFalse(error.message.orEmpty().contains(rawSecret))
            assertFalse(error.body.contains(rawSecret))
            assertTrue(error.body.contains("code=device_required"))
        }
    }

    @Test
    fun expiredHandoffUsesStableCodeAndKeepsRawUrlOutOfException() = runTest {
        var capturedHeader: String? = null
        val rawSecret = "https://upstream.example/movie.mp4?provider_token=secret"
        val api = api(
            engine = MockEngine { request ->
                capturedHeader = request.headers["X-Torve-Installation-Id"]
                respondJson(
                    body = """{"detail":{"error_code":"stream_expired","message":"$rawSecret"}}""",
                    status = HttpStatusCode.Gone,
                )
            },
        )

        try {
            api.getHandoff("opaque-token")
            fail("Expected UsenetApiException")
        } catch (error: UsenetApiException) {
            assertEquals("stream_expired", error.errorCode)
            assertEquals("install-usenet-1", capturedHeader)
            assertFalse(error.message.orEmpty().contains(rawSecret))
            assertFalse(error.body.contains(rawSecret))
            assertTrue(error.body.contains("code=stream_expired"))
        }
    }

    private fun api(engine: MockEngine): UsenetApi {
        return UsenetApi(
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
            authClient = authClient(),
            baseUrlProvider = { "https://api.torve.app" },
            installationIdProvider = { "install-usenet-1" },
        )
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
                    installation_id = "install-usenet-1",
                    device_name = "Test",
                    device_type = "desktop",
                    platform = "desktop",
                )
            },
        )
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

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
