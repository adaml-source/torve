package com.torve.data.metadata

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.account.*
import com.torve.data.auth.*
import com.torve.data.progress.PreferencesRepositoryImpl
import com.torve.db.TorveDatabase
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TmdbGuestConsentTest {
    @Test fun guestChoicePersistsLocallyWithoutAccountWritesAndGatesEveryRequest() = runTest {
        val previous = com.torve.data.addon.ExtensionRuntimePolicy.optionalMetadataExtensions
        com.torve.data.addon.ExtensionRuntimePolicy.optionalMetadataExtensions = true
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = TorveDatabase(driver).also { TorveDatabase.Schema.create(driver) }
        var requests = 0
        var dns = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("""{"page":1,"results":[],"total_pages":1,"total_results":0}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        lateinit var auth: AuthClient
        val ids = UserIdProvider { auth }
        val local = PreferencesRepositoryImpl(database, ids)
        val secure = object : SecureStorage {
            override suspend fun getString(key: String): String? = null
            override suspend fun putString(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        auth = AuthClient(local, secure, http, { "https://fixture.invalid" }, {
            DeviceRegistrationDto(installation_id = "fixture", device_name = "Fixture", device_type = "phone", platform = "ios")
        })
        val remote = object : AccountSettingsRepository {
            override val state = MutableStateFlow(AccountSettingsSyncState())
            override suspend fun syncAfterSignIn() = AccountSettingsRefreshResult()
            override suspend fun refreshIfStale(force: Boolean) = AccountSettingsRefreshResult()
            override fun scheduleSharedSettingSync(key: String, value: String?) { error("Guest choice must not sync") }
            override suspend fun clearSessionState() = Unit
        }
        try {
            var prefs = AccountAwarePreferencesRepository(local, remote, ids)
            val api = TmdbApiClient(http, { "fixture" }, { dns++; null }, requestAllowed = { prefs.tmdbExtensionSelection().permits(it) })
            assertEquals(TmdbExtensionSelection(), prefs.tmdbExtensionSelection())
            assertFailsWith<TmdbExtensionDisabledException> { api.searchMulti("fixture") }
            assertEquals(0, requests); assertEquals(0, dns)
            prefs.setString(KEY_TMDB_CATALOGUE_ENABLED, "true")
            prefs = AccountAwarePreferencesRepository(local, remote, ids) // reopen settings
            assertTrue(prefs.tmdbExtensionSelection().catalogue)
            api.searchMulti("fixture")
            assertEquals(1, requests)
            prefs.setString(KEY_TMDB_CATALOGUE_ENABLED, "false")
            assertFailsWith<TmdbExtensionDisabledException> { api.searchMulti("fixture") }
            assertEquals(1, requests)
            prefs.setString("theme_mode", "LIGHT")
            prefs = AccountAwarePreferencesRepository(local, remote, ids)
            assertEquals("LIGHT", prefs.getString("theme_mode"))
            assertEquals("LIGHT", local.getString(GUEST_THEME_MODE_KEY))
            prefs.setString("app_language", "GERMAN")
            assertEquals("GERMAN", AccountAwarePreferencesRepository(local, remote, ids).getString("app_language"))
            prefs.setString("unrelated_private_setting", "blocked")
            assertNull(prefs.getString("unrelated_private_setting"))
            prefs.remove("theme_mode")
            assertNull(prefs.getString("theme_mode"))
            prefs.remove(KEY_TMDB_CATALOGUE_ENABLED)
            assertFalse(prefs.tmdbExtensionSelection().catalogue)
        } finally {
            http.close(); driver.close()
            com.torve.data.addon.ExtensionRuntimePolicy.optionalMetadataExtensions = previous
        }
    }
}
