package com.torve.data.replays

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.account.*
import com.torve.data.auth.*
import com.torve.data.progress.PreferencesRepositoryImpl
import com.torve.db.TorveDatabase
import com.torve.domain.security.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WebReplayGuestPreferencesTest {
    @Test fun guestWebsitesAndReplayPositionsPersistWithoutAnAccountOrCloudWrite() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = TorveDatabase(driver).also { TorveDatabase.Schema.create(driver) }
        val http = HttpClient(MockEngine { error("Guest configuration must not make a network request") })
        lateinit var auth: AuthClient
        val ids = UserIdProvider { auth }
        val local = PreferencesRepositoryImpl(db, ids)
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
            override fun scheduleSharedSettingSync(key: String, value: String?) { error("Guest choices must not sync") }
            override suspend fun clearSessionState() = Unit
        }
        try {
            var prefs = AccountAwarePreferencesRepository(local, remote, ids)
            prefs.setString(WebReplayCatalogue.SETTINGS, "public-config")
            prefs = AccountAwarePreferencesRepository(local, remote, ids)
            assertEquals("public-config", prefs.getString(WebReplayCatalogue.SETTINGS))
            local.setString("web_replays_guest_web_replays_v1_progress", "saved-position")
            assertEquals("saved-position", local.getString("web_replays_guest_web_replays_v1_progress"))
            prefs.setString("private_account_setting", "must-not-save")
            assertNull(prefs.getString("private_account_setting"))
            prefs.remove(WebReplayCatalogue.SETTINGS); assertNull(prefs.getString(WebReplayCatalogue.SETTINGS))
        } finally { http.close(); driver.close() }
    }
}
