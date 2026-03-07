package com.torve.data.progress

import com.torve.db.TorveDatabase
import com.torve.domain.repository.PreferencesRepository

class PreferencesRepositoryImpl(
    private val database: TorveDatabase,
) : PreferencesRepository {

    override suspend fun getString(key: String): String? {
        return database.torveQueries.getPreference(key).executeAsOneOrNull()
    }

    override suspend fun setString(key: String, value: String) {
        database.torveQueries.setPreference(key, value)
    }

    override suspend fun remove(key: String) {
        database.torveQueries.deletePreference(key)
    }
}
