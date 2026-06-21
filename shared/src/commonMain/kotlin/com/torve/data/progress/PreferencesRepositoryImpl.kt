package com.torve.data.progress

import com.torve.data.auth.UserIdProvider
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
            key.startsWith("auth_") ||
                key.startsWith("subscription_backend_verified_") ||
                key.startsWith("channels_bootstrap_") ||
                key.startsWith("vod_bootstrap_")
    }

    private fun userIdForKeyOrNull(key: String): String? {
        if (isDeviceLocalKey(key)) return DEVICE_LOCAL_USER_ID
        return userIdProvider.currentUserIdOrNull()
    }

    override suspend fun getString(key: String): String? = withContext(ioDispatcher) {
        if (isDeviceLocalKey(key)) {
            return@withContext database.torveQueries.getPreference(
                userId = DEVICE_LOCAL_USER_ID,
                key = key,
            ).executeAsOneOrNull()
                ?: database.torveQueries.getPreference(
                    userId = "",
                    key = key,
                ).executeAsOneOrNull()
        }
        val userId = userIdForKeyOrNull(key) ?: return@withContext null
        database.torveQueries.getPreference(
            userId = userId,
            key = key,
        ).executeAsOneOrNull()
    }

    override suspend fun setString(key: String, value: String) = withContext(ioDispatcher) {
        val userId = userIdForKeyOrNull(key) ?: return@withContext
        database.torveQueries.setPreference(
            user_id = userId,
            key = key,
            value_ = value,
        )
    }

    override suspend fun remove(key: String) = withContext(ioDispatcher) {
        val userId = userIdForKeyOrNull(key) ?: return@withContext
        database.torveQueries.deletePreference(
            userId = userId,
            key = key,
        )
        if (isDeviceLocalKey(key)) {
            database.torveQueries.deletePreference(
                userId = "",
                key = key,
            )
        }
    }
}
