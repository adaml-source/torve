package com.torve.domain.repository

interface DeviceLocalSettingsRepository {
    suspend fun getString(key: String): String?
    suspend fun setString(key: String, value: String)
    suspend fun remove(key: String)
}
