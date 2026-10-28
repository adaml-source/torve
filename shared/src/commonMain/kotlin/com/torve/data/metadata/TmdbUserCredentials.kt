package com.torve.data.metadata

import com.torve.domain.security.SecureStorage
import kotlinx.coroutines.CancellationException

/** A personal v3 API key stays in device secure storage, isolated by account/profile. */
class TmdbUserCredentials(
    private val storage: SecureStorage,
    private val owner: () -> String,
) {
    private fun key() = "metadata:tmdb:api_key:${owner()}"
    suspend fun read(): String? {
        val slot = key()
        val value = storage.getString(slot)?.takeIf { it.isNotBlank() }
        if (slot != key()) throw CancellationException("The active account or profile changed")
        return value
    }
    suspend fun save(value: String) {
        val normalized = value.trim()
        require(normalized.matches(Regex("[a-fA-F0-9]{32}"))) { "Enter a TMDB API key (v3), consisting of 32 hexadecimal characters." }
        val slot = key()
        storage.putString(slot, normalized)
        check(storage.getString(slot) == normalized) { "The API key could not be saved securely. Try again." }
        if (slot != key()) throw CancellationException("The active account or profile changed")
    }
    suspend fun clear() = storage.remove(key())
}
