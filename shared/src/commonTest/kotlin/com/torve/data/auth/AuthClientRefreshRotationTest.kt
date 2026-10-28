package com.torve.data.auth

import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthClientRefreshRotationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun cached_startup_restores_identity_without_waiting_for_network_or_refreshing_tokens() = runTest {
        val storage = FakeSecureStorage(mutableMapOf(AuthClient.KEY_AUTH_ACCESS_TOKEN to "expired-access",
            AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh", "auth_token_expires_at" to "1"))
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1", AuthClient.KEY_AUTH_IS_VERIFIED to "true"))
        var requests = 0
        val client = buildAuthClient(storage, settings) { requests++; errorResponse(HttpStatusCode.ServiceUnavailable, "offline") }
        val user = client.restoreCachedSessionUser()
        assertEquals("user-1", user?.id)
        assertTrue(user?.isVerified == true)
        assertEquals(user, client.authUserFlow.value)
        assertEquals(0, requests)
        assertEquals("expired-access", storage.values[AuthClient.KEY_AUTH_ACCESS_TOKEN])
    }

    @Test
    fun cached_metadata_without_a_stored_session_cannot_restore_an_account() = runTest {
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "old@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "old-user", AuthClient.KEY_AUTH_IS_VERIFIED to "true"))
        val client = buildAuthClient(FakeSecureStorage(), settings) { error("Local restoration must never use the network") }
        assertNull(client.restoreCachedSessionUser())
        assertNull(client.authUserFlow.value)
    }

    @Test
    fun cached_startup_migrates_legacy_tokens_but_keeps_email_verification_required() = runTest {
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1", AuthClient.KEY_AUTH_IS_VERIFIED to "false",
            AuthClient.KEY_AUTH_ACCESS_TOKEN to "legacy-access", AuthClient.KEY_AUTH_REFRESH_TOKEN to "legacy-refresh"))
        val storage = FakeSecureStorage()
        val client = buildAuthClient(storage, settings) { error("Local restoration must never use the network") }
        val user = client.restoreCachedSessionUser()
        assertNotNull(user)
        assertFalse(user.isVerified)
        assertEquals("legacy-refresh", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertNull(settings.values[AuthClient.KEY_AUTH_ACCESS_TOKEN])
        assertNull(settings.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
    }

    @Test
    fun cached_startup_requires_a_nonempty_account_identity() = runTest {
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app"))
        val storage = FakeSecureStorage(mutableMapOf(AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh"))
        val client = buildAuthClient(storage, settings) { error("Local restoration must never use the network") }
        assertNull(client.restoreCachedSessionUser())
        assertNull(client.authUserFlow.value)
    }

    @Test
    fun refreshTokens_persists_rotated_refresh_token_and_uses_it_next_time() = runTest {
        val storage = FakeSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_ACCESS_TOKEN to "access_1",
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh_1",
            ),
        )
        val settings = FakeDeviceLocalSettingsRepository(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                AuthClient.KEY_AUTH_USER_ID to "user-1",
            ),
        )
        val refreshRequests = mutableListOf<String>()
        val client = buildAuthClient(storage, settings) { request ->
            val payload = parseRequestBody(request)
            refreshRequests += payload["refresh_token"] ?: ""
            when (refreshRequests.size) {
                1 -> authResponse("access_2", "refresh_2")
                2 -> authResponse("access_3", "refresh_3")
                else -> errorResponse(HttpStatusCode.Unauthorized, "stale refresh token reused")
            }
        }

        val firstRefresh = client.refreshTokens()
        val secondRefresh = client.refreshTokens()

        assertTrue(firstRefresh.success)
        assertTrue(secondRefresh.success)
        assertEquals(listOf("refresh_1", "refresh_2"), refreshRequests)
        assertEquals("access_3", storage.values[AuthClient.KEY_AUTH_ACCESS_TOKEN])
        assertEquals("refresh_3", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertTrue(storage.batchUpdates.any { it[AuthClient.KEY_AUTH_REFRESH_TOKEN] == "refresh_2" })
        assertTrue(storage.batchUpdates.any { it[AuthClient.KEY_AUTH_REFRESH_TOKEN] == "refresh_3" })
    }

    @Test
    fun getValidAccessToken_serializes_concurrent_refreshes() = runTest {
        val storage = FakeSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_ACCESS_TOKEN to "access_1",
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh_1",
                "auth_token_expires_at" to "1",
            ),
        )
        val settings = FakeDeviceLocalSettingsRepository(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                AuthClient.KEY_AUTH_USER_ID to "user-1",
            ),
        )
        var refreshCallCount = 0
        val client = buildAuthClient(storage, settings) { request ->
            refreshCallCount += 1
            val payload = parseRequestBody(request)
            assertEquals("refresh_1", payload["refresh_token"])
            delay(50)
            authResponse("access_2", "refresh_2")
        }

        val results = List(8) {
            async { client.getValidAccessToken() }
        }.awaitAll()

        assertEquals(1, refreshCallCount)
        assertEquals(List(8) { "access_2" }, results)
        assertEquals("refresh_2", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
    }

    @Test
    fun getValidAccessToken_refreshes_when_access_token_missing_but_refresh_token_exists() = runTest {
        val storage = FakeSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh_1",
            ),
        )
        val settings = FakeDeviceLocalSettingsRepository(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                AuthClient.KEY_AUTH_USER_ID to "user-1",
            ),
        )
        var refreshCallCount = 0
        val client = buildAuthClient(storage, settings) { request ->
            refreshCallCount += 1
            val payload = parseRequestBody(request)
            assertEquals("refresh_1", payload["refresh_token"])
            authResponse("access_2", "refresh_2")
        }

        val token = client.getValidAccessToken()

        assertEquals("access_2", token)
        assertEquals(1, refreshCallCount)
        assertEquals("refresh_2", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
    }

    @Test
    fun refreshTokens_revoked_family_clears_auth_and_emits_event() = runTest {
        val storage = FakeSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_ACCESS_TOKEN to "access_1",
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh_1",
            ),
        )
        val settings = FakeDeviceLocalSettingsRepository(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                AuthClient.KEY_AUTH_USER_ID to "user-1",
            ),
        )
        val client = buildAuthClient(storage, settings) {
            errorResponse(HttpStatusCode.Unauthorized, "Refresh token reuse detected")
        }
        val eventDeferred = async { client.authEvents.first() }

        val result = client.refreshTokens()
        val event = eventDeferred.await()

        assertFalse(result.success)
        assertEquals("Refresh token reuse detected", result.error)
        assertNull(storage.values[AuthClient.KEY_AUTH_ACCESS_TOKEN])
        assertNull(storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertNull(settings.values[AuthClient.KEY_AUTH_EMAIL])
        assertNull(client.getCurrentUser())
        val expiredEvent = assertIs<AuthEvent.SessionExpired>(event)
        assertEquals("Refresh token reuse detected", expiredEvent.message)
    }

    @Test
    fun getAuthenticatedUser_keeps_cached_session_on_network_refresh_failure() = runTest {
        val storage = FakeSecureStorage(
            mutableMapOf(
                AuthClient.KEY_AUTH_ACCESS_TOKEN to "access_1",
                AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh_1",
                "auth_token_expires_at" to "1",
            ),
        )
        val settings = FakeDeviceLocalSettingsRepository(
            mutableMapOf(
                AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                AuthClient.KEY_AUTH_USER_ID to "user-1",
                AuthClient.KEY_AUTH_DISPLAY_NAME to "Torve User",
            ),
        )
        val client = buildAuthClient(storage, settings) {
            throw IllegalStateException("network down")
        }

        val user = client.getAuthenticatedUser()

        assertNotNull(user)
        assertEquals("user@torve.app", user.email)
        assertEquals("refresh_1", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertEquals("Torve User", settings.values[AuthClient.KEY_AUTH_DISPLAY_NAME])
    }

    @Test
    fun cancelled_screen_cannot_lose_a_token_already_rotated_by_the_server() = runTest {
        val storage = FakeSecureStorage(mutableMapOf(AuthClient.KEY_AUTH_ACCESS_TOKEN to "old-access",
            AuthClient.KEY_AUTH_REFRESH_TOKEN to "old-refresh"))
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1"))
        val serverRotated = kotlinx.coroutines.CompletableDeferred<Unit>()
        val responseReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = buildAuthClient(storage, settings) {
            serverRotated.complete(Unit)
            responseReady.await()
            authResponse("new-access", "new-refresh")
        }
        val refresh = async { client.refreshTokens() }
        serverRotated.await()
        refresh.cancel() // A Home reload/navigation cancels the original caller.
        responseReady.complete(Unit)
        refresh.join()
        assertEquals("new-refresh", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertEquals("new-access", storage.values[AuthClient.KEY_AUTH_ACCESS_TOKEN])
    }

    @Test
    fun cold_start_does_not_rotate_an_access_token_that_is_still_valid() = runTest {
        val storage = FakeSecureStorage(mutableMapOf(AuthClient.KEY_AUTH_ACCESS_TOKEN to "valid-access",
            AuthClient.KEY_AUTH_REFRESH_TOKEN to "valid-refresh", "auth_token_expires_at" to "9999999999999"))
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1"))
        var calls = 0
        val client = buildAuthClient(storage, settings) { calls++; authResponse("new-access", "new-refresh") }
        assertTrue(client.restoreSession())
        assertEquals(0, calls)
        assertEquals("valid-refresh", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertEquals("user-1", client.authUserFlow.value?.id)
    }

    @Test
    fun offline_cold_start_preserves_the_cached_account_and_session() = runTest {
        val storage = FakeSecureStorage(mutableMapOf(AuthClient.KEY_AUTH_ACCESS_TOKEN to "expired-access",
            AuthClient.KEY_AUTH_REFRESH_TOKEN to "saved-refresh", "auth_token_expires_at" to "1"))
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1"))
        val client = buildAuthClient(storage, settings) { errorResponse(HttpStatusCode.ServiceUnavailable, "offline") }
        assertTrue(client.restoreSession())
        assertEquals("user-1", client.authUserFlow.value?.id)
        assertEquals("saved-refresh", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
    }

    @Test
    fun cold_start_does_not_restore_a_session_rejected_by_the_server() = runTest {
        val storage = FakeSecureStorage(mutableMapOf(AuthClient.KEY_AUTH_ACCESS_TOKEN to "expired-access",
            AuthClient.KEY_AUTH_REFRESH_TOKEN to "revoked-refresh", "auth_token_expires_at" to "1"))
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1"))
        val client = buildAuthClient(storage, settings) { errorResponse(HttpStatusCode.Unauthorized, "Refresh token is invalid or revoked") }
        assertFalse(client.restoreSession())
        assertNull(client.authUserFlow.value)
        assertNull(storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertNull(settings.values[AuthClient.KEY_AUTH_USER_ID])
    }

    @Test
    fun cold_start_recovers_when_only_the_refresh_token_was_saved() = runTest {
        val storage = FakeSecureStorage(mutableMapOf(AuthClient.KEY_AUTH_REFRESH_TOKEN to "saved-refresh"))
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1"))
        val client = buildAuthClient(storage, settings) { authResponse("new-access", "new-refresh") }
        assertTrue(client.restoreSession())
        assertEquals("user-1", client.authUserFlow.value?.id)
        assertEquals("new-refresh", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
    }

    @Test
    fun cold_start_refreshes_legacy_credentials_with_no_saved_expiry() = runTest {
        val storage = FakeSecureStorage()
        val settings = FakeDeviceLocalSettingsRepository(mutableMapOf(AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
            AuthClient.KEY_AUTH_USER_ID to "user-1", AuthClient.KEY_AUTH_ACCESS_TOKEN to "legacy-access",
            AuthClient.KEY_AUTH_REFRESH_TOKEN to "legacy-refresh"))
        var calls = 0
        val client = buildAuthClient(storage, settings) { calls++; authResponse("new-access", "new-refresh") }
        assertTrue(client.restoreSession())
        assertEquals(1, calls)
        assertEquals("new-refresh", storage.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
        assertNull(settings.values[AuthClient.KEY_AUTH_REFRESH_TOKEN])
    }

    private fun buildAuthClient(
        storage: FakeSecureStorage,
        settings: FakeDeviceLocalSettingsRepository,
        refreshHandler: suspend (HttpRequestData) -> MockResponse,
    ): AuthClient {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/auth/refresh") -> {
                    val response = refreshHandler(request)
                    respond(
                        content = response.body,
                        status = response.status,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected request ${request.url}")
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        return AuthClient(
            localSettingsRepository = settings,
            secureStorage = storage,
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    device_id = "device-1",
                    installation_id = "install-1",
                    device_name = "Pixel",
                    device_type = "phone",
                    platform = "android",
                )
            },
        )
    }

    private fun authResponse(accessToken: String, refreshToken: String) = MockResponse(
        body = """
            {
              "user": {
                "id": "user-1",
                "email": "user@torve.app",
                "display_name": "Torve User",
                "is_verified": true
              },
              "tokens": {
                "access_token": "$accessToken",
                "refresh_token": "$refreshToken",
                "expires_in": 900
              },
              "device": {
                "id": "device-1"
              }
            }
        """.trimIndent(),
        status = HttpStatusCode.OK,
    )

    private fun errorResponse(status: HttpStatusCode, detail: String) = MockResponse(
        body = """{"detail":"$detail"}""",
        status = status,
    )

    private suspend fun parseRequestBody(request: HttpRequestData): Map<String, String> {
        val bodyText = when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.ReadChannelContent -> body.readFrom().readRemaining().readText()
            is OutgoingContent.WriteChannelContent -> {
                val channel = ByteChannel(autoFlush = true)
                body.writeTo(channel)
                channel.close()
                channel.readRemaining().readText()
            }
            else -> error("Unsupported body type: ${body::class}")
        }
        val element = json.parseToJsonElement(bodyText).jsonObject
        return buildMap {
            element.forEach { (key, value) ->
                put(key, value.toString().trim('"'))
            }
        }
    }
}

private data class MockResponse(
    val body: String,
    val status: HttpStatusCode,
)

private class FakeSecureStorage(
    val values: MutableMap<String, String?> = mutableMapOf(),
) : SecureStorage {
    val batchUpdates = mutableListOf<Map<String, String?>>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun updateStrings(updates: Map<String, String?>) {
        batchUpdates += updates.toMap()
        updates.forEach { (key, value) ->
            if (value == null) {
                values.remove(key)
            } else {
                values[key] = value
            }
        }
    }
}

private class FakeDeviceLocalSettingsRepository(
    val values: MutableMap<String, String?> = mutableMapOf(),
) : DeviceLocalSettingsRepository {
    override suspend fun getString(key: String): String? = values[key]

    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
