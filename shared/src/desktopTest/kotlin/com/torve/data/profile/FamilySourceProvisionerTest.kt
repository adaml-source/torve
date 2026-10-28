package com.torve.data.profile

import com.torve.data.PersonalDeviceFixture
import com.torve.data.auth.profileDataUserId
import com.torve.domain.integrations.*
import com.torve.domain.model.*
import com.torve.presentation.settings.SettingsRefreshNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FamilySourceProvisionerTest {
    private fun client() = HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
    @Test fun selectedConnectionsAreCopiedWithoutCopyingHistoryOrPlaintextSecrets() = runTest {
        PersonalDeviceFixture(client()).use { device ->
            device.signIn()
            val q = device.database.torveQueries
            val source = device.ids.currentDataUserId(); val target = profileDataUserId(device.ids.currentUserId(), "kids")
            q.insertProfile(device.ids.currentUserId(), "kids", "Kids", 0, 0, null, null, 2)
            q.insertAddon(source, "https://approved.test/manifest.json", "approved", "Approved", "1", null, null, "{}", 1, 0, 1, null, null, "app", null)
            q.insertAddon(source, "https://other.test/manifest.json", "other", "Other", "1", null, null, "{}", 1, 0, 1, null, null, "app", null)
            q.setPreference(source, "jellyfin_server_url", "https://nas.test")
            q.setPreference(source, "jellyfin_selected_user_id", "nas-parent")
            q.setPreference(source, "private-personal-setting", "parent-only")
            device.secrets.put(IntegrationSecretKey.JELLYFIN_API_KEY, "test-only-private-key")
            val family = FamilyControls(device.preferences, device.secure, device.ids, SettingsRefreshNotifier(), lookup = { emptyList() }, scope = backgroundScope)
            family.initialize(); family.enable("2468", "2468")
            FamilySourceProvisioner(device.database, device.ids, device.secrets, family).provision("kids",
                KidsProfileRules(allowedSources = setOf("extension:approved", "jellyfin:child-library")))
            assertEquals(listOf("approved"), q.getAllAddons(target).executeAsList().map { it.id })
            assertEquals("https://nas.test", q.getPreference(target, "jellyfin_server_url").executeAsOneOrNull())
            assertNull(q.getPreference(target, "private-personal-setting").executeAsOneOrNull())
            assertTrue(q.getAllPreferences(target).executeAsList().none { "private-key" in it.value_ })
            device.select("kids")
            assertEquals("test-only-private-key", device.secrets.get(IntegrationSecretKey.JELLYFIN_API_KEY))
            assertEquals(IntegrationStorageMode.DEVICE_ONLY, device.secrets.getStorageMode(IntegrationSecretKey.JELLYFIN_API_KEY))
        }
    }
    @Test fun accountCleanupCannotEraseTheDeviceFamilyBoundary() = runTest {
        PersonalDeviceFixture(client()).use { device ->
            device.signIn()
            device.preferences.setString("family_device_configuration_v1", "protected-policy")
            device.preferences.setString("family_device_pin_attempts_v1", "3")
            device.preferences.setString("theme_mode", "DARK")
            device.database.torveQueries.deleteAllAccountPreferences()
            assertEquals("protected-policy", device.preferences.getString("family_device_configuration_v1"))
            assertEquals("3", device.preferences.getString("family_device_pin_attempts_v1"))
            assertNull(device.preferences.getString("theme_mode"))
        }
    }
}
