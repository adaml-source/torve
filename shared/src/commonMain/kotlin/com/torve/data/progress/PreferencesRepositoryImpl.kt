package com.torve.data.progress

import com.torve.data.auth.UserIdProvider
import com.torve.db.TorveDatabase
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository

class PreferencesRepositoryImpl(
    private val database: TorveDatabase,
    private val userIdProvider: UserIdProvider,
) : PreferencesRepository, DeviceLocalSettingsRepository {

    override suspend fun getString(key: String): String? {
        return database.torveQueries.getPreference(
            userId = userIdProvider.currentUserId(),
            key = key,
        ).executeAsOneOrNull()
    }

    override suspend fun setString(key: String, value: String) {
        database.torveQueries.setPreference(
            user_id = userIdProvider.currentUserId(),
            key = key,
            value_ = value,
        )
    }

    override suspend fun remove(key: String) {
        database.torveQueries.deletePreference(
            userId = userIdProvider.currentUserId(),
            key = key,
        )
    }
}
