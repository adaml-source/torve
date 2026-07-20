package com.torve.data.account

import com.torve.presentation.settings.AppLanguage
import com.torve.presentation.settings.SettingsViewModel

object AccountSettingsSyncPolicy {
    const val KEY_HOME_SECTION_CONFIGS = "home_section_configs"
    const val KEY_HOME_LAYOUT_ORDER = "home_layout_order"
    const val KEY_ENABLED_STREAMING_SERVICES = "enabled_streaming_services"
    const val KEY_ADDON_SHELF_VISIBILITY = "addon_shelf_visibility"
    const val KEY_LANGUAGE = "language"

    private val sharedKeys = setOf(
        // Appearance & layout
        SettingsViewModel.KEY_APP_LANGUAGE,
        SettingsViewModel.KEY_RATING_PREFS,
        SettingsViewModel.KEY_CARD_STYLE_PRESETS,
        SettingsViewModel.KEY_CARD_DEFAULT_PRESET_ID,
        KEY_HOME_SECTION_CONFIGS,
        KEY_HOME_LAYOUT_ORDER,
        KEY_ENABLED_STREAMING_SERVICES,
        KEY_ADDON_SHELF_VISIBILITY,
        // Content preferences (non-sensitive)
        SettingsViewModel.KEY_REGION_CODE,
        SettingsViewModel.KEY_STREAM_GROUPS,
        SettingsViewModel.KEY_REGEX_PATTERNS,
        SettingsViewModel.KEY_DEDUPE_RESULTS,
        // Provider selection (non-sensitive flag, not the credential)
        SettingsViewModel.KEY_DEBRID_PROVIDER,
        SettingsViewModel.KEY_AI_PROVIDER,
    )

    fun isSharedKey(key: String): Boolean = key in sharedKeys

    fun sharedKeys(): Set<String> = sharedKeys

    fun outgoingRemoteSettings(localKey: String, value: String?): Map<String, String?> = when (localKey) {
        SettingsViewModel.KEY_APP_LANGUAGE -> mapOf(
            KEY_LANGUAGE to normalizeLanguageForRemote(value),
            // Do not send app_language: null — explicitNulls=false drops it from JSON.
            // The canonical 'language' key is the source of truth; readers prefer it.
        )
        else -> mapOf(localKey to value)
    }

    fun normalizeRemoteSettings(remoteSettings: Map<String, String>): Map<String, String> {
        val normalized = linkedMapOf<String, String>()
        decodeRemoteLanguage(
            canonical = remoteSettings[KEY_LANGUAGE],
            legacy = remoteSettings[SettingsViewModel.KEY_APP_LANGUAGE],
        )?.let { normalized[SettingsViewModel.KEY_APP_LANGUAGE] = it.name }

        for ((key, value) in remoteSettings) {
            if (key == KEY_LANGUAGE || key == SettingsViewModel.KEY_APP_LANGUAGE) continue
            if (isSharedKey(key)) normalized[key] = value
        }
        return normalized
    }

    internal fun normalizeLanguageForRemote(localValue: String?): String? {
        val language = parseLanguage(localValue) ?: return null
        return language.code
    }

    internal fun decodeRemoteLanguage(
        canonical: String?,
        legacy: String?,
    ): AppLanguage? {
        return parseLanguage(canonical) ?: parseLanguage(legacy)
    }

    private fun parseLanguage(raw: String?): AppLanguage? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AppLanguage.entries.firstOrNull { entry ->
            entry.name.equals(value, ignoreCase = true) || entry.code.equals(value, ignoreCase = true)
        }
    }
}
