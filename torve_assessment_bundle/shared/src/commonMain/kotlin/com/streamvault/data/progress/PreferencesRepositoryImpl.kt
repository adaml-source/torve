package com.streamvault.data.progress

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.repository.PreferencesRepository

class PreferencesRepositoryImpl(
    private val database: StreamVaultDatabase,
) : PreferencesRepository {

    override suspend fun getString(key: String): String? {
        return database.streamVaultQueries.getPreference(key).executeAsOneOrNull()
    }

    override suspend fun setString(key: String, value: String) {
        database.streamVaultQueries.setPreference(key, value)
    }

    override suspend fun remove(key: String) {
        database.streamVaultQueries.deletePreference(key)
    }
}
