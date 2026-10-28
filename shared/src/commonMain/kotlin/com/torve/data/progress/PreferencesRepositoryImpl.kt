package com.torve.data.progress

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.checkPersonalScope
import com.torve.db.TorveDatabase
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.util.ioDispatcher
import kotlinx.coroutines.withContext

class PreferencesRepositoryImpl(
    private val database: TorveDatabase,
    private val userIdProvider: UserIdProvider,
) : PreferencesRepository, DeviceLocalSettingsRepository {

    private companion object {
        const val DEVICE_LOCAL_USER_ID = "__torve_device_local__"

        fun isDeviceLocalKey(key: String): Boolean =
            key == com.torve.data.account.GUEST_EPISODE_BADGE_KEY ||
            key == com.torve.data.account.GUEST_THEME_MODE_KEY ||
            key == com.torve.data.account.GUEST_APP_LANGUAGE_KEY ||
            key == "metadata_guest_metadata_tmdb_catalogue_enabled" ||
                key == "metadata_guest_metadata_tmdb_enrichment_enabled" ||
            key.startsWith("web_replays_guest_") ||
            key.startsWith("family_device_") ||
            key.startsWith("auth_") ||
                key.startsWith("subscription_backend_verified_") ||
                key.startsWith("channels_bootstrap_") ||
                key.startsWith("vod_bootstrap_")
    }

    private suspend fun userIdForKeyOrNull(key: String): String? {
        userIdProvider.checkPersonalScope()
        if (isDeviceLocalKey(key)) return DEVICE_LOCAL_USER_ID
        if (key.startsWith("profilepref_v1:")) {
            val profile = key.removePrefix("profilepref_v1:").substringBefore(':')
            return userIdProvider.currentUserIdOrNull()?.let { com.torve.data.auth.profileDataUserId(it, profile) }
        }
        if (key.startsWith("account_") || key.startsWith("household_sync_") || key.startsWith("household_profile_"))
            return userIdProvider.currentUserIdOrNull()
        return userIdProvider.currentDataUserIdOrNull()
    }

    private fun storageKey(key: String) = if (key.startsWith("profilepref_v1:"))
        key.removePrefix("profilepref_v1:").substringAfter(':') else key

    override suspend fun getString(key: String): String? = withContext(ioDispatcher) {
        if (isDeviceLocalKey(key)) {
            return@withContext database.torveQueries.readPreferenceSafely(DEVICE_LOCAL_USER_ID, key)
                ?: database.torveQueries.readPreferenceSafely("", key)
        }
        val userId = userIdForKeyOrNull(key) ?: return@withContext null
        database.torveQueries.readPreferenceSafely(userId, storageKey(key))
    }

    override suspend fun setString(key: String, value: String) = withContext(ioDispatcher) {
        val userId = userIdForKeyOrNull(key) ?: return@withContext
        database.torveQueries.setPreference(
            user_id = userId,
            key = storageKey(key),
            value_ = value,
        )
    }

    override suspend fun remove(key: String) = withContext(ioDispatcher) {
        val userId = userIdForKeyOrNull(key) ?: return@withContext
        database.torveQueries.deletePreference(
            userId = userId,
            key = storageKey(key),
        )
        if (isDeviceLocalKey(key)) {
            database.torveQueries.deletePreference(
                userId = "",
                key = key,
            )
        }
    }
}
