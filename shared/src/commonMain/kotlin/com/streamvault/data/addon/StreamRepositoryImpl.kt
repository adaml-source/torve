package com.streamvault.data.addon

import com.streamvault.data.debrid.DebridClient
import com.streamvault.domain.model.DebridServiceType
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.ResolvedStream
import com.streamvault.domain.repository.StreamRepository

class StreamRepositoryImpl(
    private val addonClient: StremioAddonClient,
    private val debridClient: DebridClient,
) : StreamRepository {

    // Default addon URLs; user can add more from settings
    private val addonUrls = mutableListOf(StremioAddonClient.TORRENTIO_BASE)

    fun addAddonUrl(url: String) {
        if (url !in addonUrls) addonUrls.add(url)
    }

    fun removeAddonUrl(url: String) {
        addonUrls.remove(url)
    }

    fun getAddonUrls(): List<String> = addonUrls.toList()

    override suspend fun fetchStreams(
        type: MediaType,
        imdbId: String,
        season: Int?,
        episode: Int?,
    ): List<ParsedStream> {
        return addonClient.fetchAllStreams(addonUrls, type, imdbId, season, episode)
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
