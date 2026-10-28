package com.torve.data.account

import com.torve.data.auth.UserIdProvider
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository

class AccountAwarePreferencesRepository(
    private val localSettingsRepository: DeviceLocalSettingsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val userIdProvider: UserIdProvider,
) : PreferencesRepository {
    // Public choices work before sign-in, but never become an account's
    // consent or expose that account's settings after signing out.
    private fun guestMetadataKey(key: String): String? = when (key) {
        com.torve.presentation.settings.SettingsViewModel.KEY_SHOW_REMAINING_EPISODES -> GUEST_EPISODE_BADGE_KEY
        com.torve.presentation.settings.SettingsViewModel.KEY_THEME_MODE -> GUEST_THEME_MODE_KEY
        com.torve.presentation.settings.SettingsViewModel.KEY_APP_LANGUAGE -> GUEST_APP_LANGUAGE_KEY
        com.torve.data.metadata.KEY_TMDB_CATALOGUE_ENABLED,
        com.torve.data.metadata.KEY_TMDB_ENRICHMENT_ENABLED -> "metadata_guest_$key"
        "web_replays_v1_settings", "web_replays_v1_cache", "web_replays_v1_history", "web_replays_v1_progress" -> "web_replays_guest_$key"
        else -> null
    }

    override suspend fun getString(key: String): String? {
        if (!userIdProvider.isSignedIn()) return guestMetadataKey(key)?.let { localSettingsRepository.getString(it) }
        return localSettingsRepository.getString(key)
    }

    override suspend fun setString(key: String, value: String) {
        if (!userIdProvider.isSignedIn()) {
            guestMetadataKey(key)?.let { localSettingsRepository.setString(it, value) }
            return
        }
        val remote = remoteKey(key)
        localSettingsRepository.setString(key, value)
        accountSettingsRepository.scheduleSharedSettingSync(remote, value)
    }

    override suspend fun remove(key: String) {
        if (!userIdProvider.isSignedIn()) {
            guestMetadataKey(key)?.let { localSettingsRepository.remove(it) }
            return
        }
        val remote = remoteKey(key)
        localSettingsRepository.remove(key)
        accountSettingsRepository.scheduleSharedSettingSync(remote, null)
    }

    private fun remoteKey(key: String): String = if (userIdProvider.currentProfileId() == "default") key
        else "profilepref_v1:${userIdProvider.currentProfileId()}:$key"
}

internal const val GUEST_EPISODE_BADGE_KEY = "appearance_guest_show_remaining_episodes"

internal const val GUEST_THEME_MODE_KEY = "appearance_guest_theme_mode"
internal const val GUEST_APP_LANGUAGE_KEY = "appearance_guest_app_language"
