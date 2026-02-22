package com.streamvault.domain.repository

import com.streamvault.data.addon.ParsedStream
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.ResolvedStream

interface StreamRepository {
    /**
     * Fetch available streams from all configured addons for a given media item.
     */
    suspend fun fetchStreams(
        type: MediaType,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
    ): List<ParsedStream>

    /**
     * Resolve a stream via debrid service to get a playable URL.
     * For torrent streams (infoHash), goes through the full debrid pipeline.
     * For direct URL streams, passes through or unrestricts.
     */
    suspend fun resolveStream(
        stream: ParsedStream,
        provider: DebridServiceType,
        apiKey: String,
    ): ResolvedStream
}
