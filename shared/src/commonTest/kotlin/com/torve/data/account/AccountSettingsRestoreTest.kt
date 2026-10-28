package com.torve.data.account

import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AccountSettingsRestoreTest {
    @Test fun serverPreferencesAndMutedConnectionsWinOnAFreshAppleDevice() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method, "Restore must not overwrite the saved account settings")
            assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
            requests++
            respond("""{"settings":{"theme_mode":"DARK","rating_display_prefs":"saved-ratings","connection_alert_muted_categories":"[\"IPTV\"]"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val prefs = RestoreLocalPreferences(mutableMapOf("profilepref_v1:default:theme_mode" to "LIGHT"))
        val notifier = SettingsRefreshNotifier()
        val repo = AccountSettingsRepositoryImpl(auth(http), AccountSettingsApi(http, { "https://fixture.invalid" }), prefs, notifier)
        val result = repo.syncAfterSignIn()
        assertNull(result.error)
        assertTrue(result.appliedChanges)
        assertEquals("DARK", prefs.values["profilepref_v1:default:theme_mode"])
        assertEquals("saved-ratings", prefs.values["profilepref_v1:default:rating_display_prefs"])
        assertEquals("[\"IPTV\"]", prefs.values["profilepref_v1:default:connection_alert_muted_categories"])
        assertEquals(1, requests)
        http.close()
    }
    @Test fun failedRestorePreservesExistingLocalSettingsAndReportsFailure() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json")) })
        val prefs = RestoreLocalPreferences(mutableMapOf("profilepref_v1:default:theme_mode" to "DARK"))
        val repo = AccountSettingsRepositoryImpl(auth(http), AccountSettingsApi(http, { "https://fixture.invalid" }), prefs, SettingsRefreshNotifier())
        val result = repo.syncAfterSignIn()
        assertNotNull(result.error)
        assertNotNull(repo.state.value.lastError)
        assertFalse(repo.state.value.isRefreshing)
        assertEquals("DARK", prefs.values["profilepref_v1:default:theme_mode"])
        http.close()
    }
    private fun auth(http: HttpClient) = AuthClient(RestoreLocalPreferences(), object : SecureStorage {
        override suspend fun getString(key: String): String? = if (key == AuthClient.KEY_AUTH_ACCESS_TOKEN) "fixture" else null
        override suspend fun putString(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
    }, http, { "https://fixture.invalid" }, { DeviceRegistrationDto(device_id = "fixture", installation_id = "fixture-install", device_name = "iPhone", device_type = "phone", platform = "ios") })
}
private class RestoreLocalPreferences(val values: MutableMap<String, String> = mutableMapOf()) : DeviceLocalSettingsRepository {
    override suspend fun getString(key: String) = values[key]
    override suspend fun setString(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}
