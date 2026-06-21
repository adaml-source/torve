package com.torve.domain.security

interface SecureStorage {
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun removeByPrefix(prefix: String) = Unit

    suspend fun updateStrings(updates: Map<String, String?>) {
        updates.forEach { (key, value) ->
            if (value == null) {
                remove(key)
            } else {
                putString(key, value)
            }
        }
    }
}
