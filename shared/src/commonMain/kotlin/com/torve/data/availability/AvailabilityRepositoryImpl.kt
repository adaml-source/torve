package com.torve.data.availability

import com.torve.domain.integrations.AvailabilityProvider
import com.torve.domain.model.AvailabilityResult
import com.torve.domain.model.MediaType
import com.torve.domain.repository.AvailabilityRepository
import com.torve.domain.repository.PreferencesRepository
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AvailabilityRepositoryImpl(
    private val provider: AvailabilityProvider,
    private val prefsRepo: PreferencesRepository,
    private val json: Json,
) : AvailabilityRepository {

    override suspend fun getAvailability(
        tmdbId: Int,
        mediaType: MediaType,
        region: String,
    ): AvailabilityResult {
        val key = "${mediaType.name}:$tmdbId:${region.uppercase()}"
        val cache = loadCache().toMutableMap()
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = cache[key]
        if (cached != null && (now - cached.fetchedAt) <= CACHE_TTL_MS) {
            return cached.result
        }

        val fresh = provider.getAvailability(tmdbId, mediaType, region)
        cache[key] = CacheEntry(result = fresh, fetchedAt = now)
        persistCache(cache)
        return fresh
    }

    private suspend fun loadCache(): Map<String, CacheEntry> {
        val raw = prefsRepo.getString(KEY_CACHE) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, CacheEntry>>(raw)
        }.getOrDefault(emptyMap())
    }

    private suspend fun persistCache(cache: Map<String, CacheEntry>) {
        val trimmed = trimExpired(cache)
        prefsRepo.setString(KEY_CACHE, json.encodeToString(trimmed))
        prefsRepo.setString(KEY_LAST_SYNC, Clock.System.now().toEpochMilliseconds().toString())
    }

    private fun trimExpired(cache: Map<String, CacheEntry>): Map<String, CacheEntry> {
        val now = Clock.System.now().toEpochMilliseconds()
        return cache.filterValues { entry -> now - entry.fetchedAt <= CACHE_TTL_MS }
    }

    @Serializable
    private data class CacheEntry(
        val result: AvailabilityResult,
        val fetchedAt: Long,
    )

    companion object {
        private const val KEY_CACHE = "availability_cache_v1"
        private const val KEY_LAST_SYNC = "availability_last_sync_time"
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
