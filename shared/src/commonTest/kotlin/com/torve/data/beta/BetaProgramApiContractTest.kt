package com.torve.data.beta

import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.beta.BetaProgramError
import com.torve.domain.beta.BetaProgramException
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BetaProgramApiContractTest {
    @Test
    fun getBetaStatusSuccessParsesResponse() = runTest {
        val api = apiRespondingWith(
            status = HttpStatusCode.OK,
            body = """
                {
                  "discord_linked": true,
                  "beta_application_status": "submitted",
                  "can_apply": false,
                  "blocked_reason": "none"
                }
            """.trimIndent(),
        )

        val status = api.getBetaStatus()

        assertEquals(true, status.discordLinked)
        assertEquals("submitted", status.betaApplicationStatus)
    }

    @Test
    fun generateDiscordBetaLinkCodeSuccessParsesResponse() = runTest {
        val api = apiRespondingWith(
            status = HttpStatusCode.OK,
            body = """
                {
                  "code": "BETA-ABC123",
                  "expires_at": "2026-06-01T12:00:00Z",
                  "discord_invite_url": "https://discord.example/invite"
                }
            """.trimIndent(),
        )

        val code = api.generateDiscordBetaLinkCode()

        assertEquals("BETA-ABC123", code.code)
        assertEquals("2026-06-01T12:00:00Z", code.expiresAt)
    }

    @Test
    fun mapsKnownBackendErrorsToDomainErrors() = runTest {
        val cases = listOf(
            HttpStatusCode.Forbidden to "email_not_verified" to BetaProgramError.EmailNotVerified,
            HttpStatusCode.Forbidden to "beta_signup_closed" to BetaProgramError.SignupClosed,
            HttpStatusCode.Forbidden to "beta_access_ended" to BetaProgramError.AccessEnded,
            HttpStatusCode.TooManyRequests to "rate_limited" to BetaProgramError.RateLimited,
            HttpStatusCode.Unauthorized to "auth_required" to BetaProgramError.AuthRequired,
            HttpStatusCode.ServiceUnavailable to "beta_unavailable" to BetaProgramError.BetaUnavailable,
        )

        cases.forEach { (statusAndCode, expected) ->
            val (status, code) = statusAndCode
            val api = apiRespondingWith(
                status = status,
                body = errorBody(code, "Raw backend detail that must not reach the UI"),
            )

            val error = runCatching { api.generateDiscordBetaLinkCode() }
                .exceptionOrNull()

            assertIs<BetaProgramException>(error)
            assertEquals(expected, error.error)
        }
    }

    @Test
    fun networkFailureMapsToNetworkError() = runTest {
        val api = BetaProgramApi(
            httpClient = HttpClient(MockEngine { error("offline") }),
            authClient = authClient(),
            baseUrlProvider = { "https://api.test" },
        )

        val error = runCatching { api.getBetaStatus() }.exceptionOrNull()

        assertIs<BetaProgramException>(error)
        assertEquals(BetaProgramError.Network, error.error)
    }

    private fun apiRespondingWith(
        status: HttpStatusCode,
        body: String,
    ): BetaProgramApi {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        return BetaProgramApi(
            httpClient = client,
            authClient = authClient(),
            baseUrlProvider = { "https://api.test" },
        )
    }

    private fun errorBody(code: String, message: String): String {
        return """{"detail":{"code":"$code","message":"$message"}}"""
    }

    private fun authClient(): AuthClient {
        return AuthClient(
            localSettingsRepository = FakeDeviceLocalSettingsRepository(
                mutableMapOf(
                    AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                    AuthClient.KEY_AUTH_USER_ID to "user-1",
                    AuthClient.KEY_AUTH_IS_VERIFIED to "true",
                ),
            ),
            secureStorage = FakeSecureStorage(
                mutableMapOf(
                    AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
                    AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
                ),
            ),
            httpClient = HttpClient(MockEngine { respond("{}") }),
            baseUrlProvider = { "https://api.test" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "installation-1",
                    device_name = "Test",
                    device_type = "desktop",
                    platform = "test",
                )
            },
        )
    }
}

private class FakeDeviceLocalSettingsRepository(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : DeviceLocalSettingsRepository {
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun setString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class FakeSecureStorage(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : SecureStorage {
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }
    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
