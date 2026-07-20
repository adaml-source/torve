package com.torve.data.account

import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountSettingsSyncPolicyTest {
    @Test
    fun sharedKeys_include_account_level_preferences() {
        assertTrue(AccountSettingsSyncPolicy.isSharedKey(SettingsViewModel.KEY_APP_LANGUAGE))
        assertTrue(AccountSettingsSyncPolicy.isSharedKey(SettingsViewModel.KEY_RATING_PREFS))
        assertTrue(AccountSettingsSyncPolicy.isSharedKey(AccountSettingsSyncPolicy.KEY_HOME_LAYOUT_ORDER))
        assertTrue(AccountSettingsSyncPolicy.isSharedKey(AccountSettingsSyncPolicy.KEY_ENABLED_STREAMING_SERVICES))
    }

    @Test
    fun sharedKeys_exclude_device_local_preferences() {
        assertFalse(AccountSettingsSyncPolicy.isSharedKey("preferred_decoder_mode"))
        assertFalse(AccountSettingsSyncPolicy.isSharedKey("audio_passthrough_enabled"))
        assertFalse(AccountSettingsSyncPolicy.isSharedKey("remote_input_quirks"))
    }

    @Test
    fun outgoingRemoteSettings_sends_canonical_language_only() {
        val payload = AccountSettingsSyncPolicy.outgoingRemoteSettings(
            SettingsViewModel.KEY_APP_LANGUAGE,
            AppLanguage.GERMAN.name,
        )

        assertEquals("de", payload[AccountSettingsSyncPolicy.KEY_LANGUAGE])
        // Legacy key must not be present — explicitNulls=false drops null values,
        // so sending null would be a no-op. Only the canonical key should be in the map.
        assertFalse(SettingsViewModel.KEY_APP_LANGUAGE in payload)
    }

    @Test
    fun outgoingRemoteSettings_maps_all_language_codes_correctly() {
        for (lang in AppLanguage.entries) {
            val payload = AccountSettingsSyncPolicy.outgoingRemoteSettings(
                SettingsViewModel.KEY_APP_LANGUAGE,
                lang.name,
            )
            assertEquals(lang.code, payload[AccountSettingsSyncPolicy.KEY_LANGUAGE])
        }
    }

    @Test
    fun normalizeRemoteSettings_prefers_canonical_language_code() {
        val normalized = AccountSettingsSyncPolicy.normalizeRemoteSettings(
            mapOf(
                AccountSettingsSyncPolicy.KEY_LANGUAGE to "de",
                SettingsViewModel.KEY_APP_LANGUAGE to "ENGLISH",
            ),
        )

        assertEquals(AppLanguage.GERMAN.name, normalized[SettingsViewModel.KEY_APP_LANGUAGE])
    }

    @Test
    fun normalizeRemoteSettings_falls_back_to_legacy_language_when_canonical_absent() {
        val normalized = AccountSettingsSyncPolicy.normalizeRemoteSettings(
            mapOf(SettingsViewModel.KEY_APP_LANGUAGE to "SPANISH"),
        )

        assertEquals(AppLanguage.SPANISH.name, normalized[SettingsViewModel.KEY_APP_LANGUAGE])
    }
}
