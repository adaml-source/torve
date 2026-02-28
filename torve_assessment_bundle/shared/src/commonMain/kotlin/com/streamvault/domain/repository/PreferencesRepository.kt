package com.streamvault.domain.repository

interface PreferencesRepository {
    suspend fun getString(key: String): String?
    suspend fun setString(key: String, value: String)
    suspend fun remove(key: String)
}
