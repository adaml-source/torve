package com.streamvault.data.addon

import com.streamvault.data.debrid.DebridClient
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.InstalledAddon
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.ResolvedStream
import com.streamvault.domain.model.StreamPreferences
import com.streamvault.domain.repository.StreamRepository

class StreamRepositoryImpl(
    private val addonClient: StremioAddonClient,
    private val debridClient: DebridClient,
    private val streamAggregator: StreamAggregator,
) : StreamRepository {

    override suspend fun fetchStreams(
        type: MediaType,
        imdbId: String,
        season: Int?,
        episode: Int?,
        addons: List<InstalledAddon>,
        debridAccounts: Map<DebridServiceType, String>,
        preferences: StreamPreferences,
    ): List<ParsedStream> {
        return streamAggregator.resolveStreams(
            addons = addons,
            type = type,
            imdbId = imdbId,
            season = season,
            episode = episode,
            debridAccounts = debridAccounts,
            preferences = preferences,
        )
    }

    override suspend fun resolveStream(
        stream: ParsedStream,
        provider: DebridServiceType,
        apiKey: String,
    ): ResolvedStream {
        // If stream has a direct URL (no torrent), try to unrestrict it
        if (stream.directUrl != null && stream.infoHash == null) {
            return debridClient.unrestrictUrl(provider, apiKey, stream.directUrl)
        }

        // Torrent stream: resolve through debrid
        val infoHash = stream.infoHash
            ?: throw Exception("Stream has no infoHash or direct URL")

        return debridClient.resolveStream(
            provider = provider,
            apiKey = apiKey,
            infoHash = infoHash,
            fileIdx = stream.fileIdx,
        )
    }
}
