package com.torve.domain.providerhealth

import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent cache of provider-health rows. Survives restarts so the UI
 * can render last-known state instantly, while live re-checks update the
 * flow as they complete.
 */
interface ProviderHealthRepository {
    /** Hot cache of all known entries. Empty until [load] runs. */
    val entries: StateFlow<List<ProviderHealthEntry>>

    /** Hydrates [entries] from persistent storage. Idempotent. */
    suspend fun load()

    /**
     * Upsert by `(category, providerKey)`. Merges with existing rows and
     * persists. Updates [entries] atomically.
     */
    suspend fun upsert(entry: ProviderHealthEntry)

    /** Remove a single entry by `(category, providerKey)`. */
    suspend fun remove(category: ProviderHealthCategory, providerKey: String)

    /** Wipe everything. Used on sign-out. */
    suspend fun clear()
}
