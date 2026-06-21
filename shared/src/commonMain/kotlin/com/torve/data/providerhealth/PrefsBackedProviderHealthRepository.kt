package com.torve.data.providerhealth

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [ProviderHealthRepository] backed by the shared key/value
 * [PreferencesRepository]. Stores all entries as a single JSON array
 * under [KEY] so reads/writes are simple.
 */
class PrefsBackedProviderHealthRepository(
    private val prefs: PreferencesRepository,
) : ProviderHealthRepository {

    private val _entries = MutableStateFlow<List<ProviderHealthEntry>>(emptyList())
    override val entries: StateFlow<List<ProviderHealthEntry>> = _entries.asStateFlow()

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ProviderHealthEntry.serializer())

    override suspend fun load() {
        mutex.withLock {
            val raw = runCatching { prefs.getString(KEY) }.getOrNull()
            if (raw.isNullOrBlank()) {
                _entries.value = emptyList()
                return@withLock
            }
            val decoded = runCatching { json.decodeFromString(serializer, raw) }
                .getOrDefault(emptyList())
            _entries.value = decoded
        }
    }

    override suspend fun upsert(entry: ProviderHealthEntry) {
        mutex.withLock {
            val current = _entries.value
            val merged = current
                .filterNot { it.category == entry.category && it.providerKey == entry.providerKey }
                .plus(entry)
            persist(merged)
        }
    }

    override suspend fun remove(category: ProviderHealthCategory, providerKey: String) {
        mutex.withLock {
            val current = _entries.value
            val pruned = current.filterNot { it.category == category && it.providerKey == providerKey }
            if (pruned.size != current.size) persist(pruned)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            _entries.value = emptyList()
            runCatching { prefs.remove(KEY) }
        }
    }

    private suspend fun persist(next: List<ProviderHealthEntry>) {
        _entries.value = next
        runCatching { prefs.setString(KEY, json.encodeToString(serializer, next)) }
    }

    companion object {
        const val KEY = "provider_health_entries_v1"
    }
}
