package com.torve.domain.repository

import com.torve.data.addon.ParsedStream
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import com.torve.domain.model.ResolvedStream
import com.torve.domain.model.StreamPreferences

interface StreamRepository {
    /**
     * Fetch available streams from all configured addons for a given media item.
     * When [addons], [debridAccounts], or [preferences] are provided, the full
     * aggregation pipeline runs: deduplicate, cache check, filter, score, sort.
     */
    suspend fun fetchStreams(
        type: MediaType,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        addons: List<InstalledAddon> = emptyList(),
        debridAccounts: Map<DebridServiceType, String> = emptyMap(),
        preferences: StreamPreferences = StreamPreferences(),
    ): List<ParsedStream>

    /**
     * Resolve a stream via debrid service to get a playable URL.
     * For hash-based streams (infoHash), goes through the full debrid pipeline.
     * For direct URL streams, passes through or unrestricts.
     */
    suspend fun resolveStream(
        stream: ParsedStream,
        provider: DebridServiceType,
        apiKey: String,
    ): ResolvedStream
}
