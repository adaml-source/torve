package com.torve.data.download

import com.torve.data.addon.StreamSelector
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.DeviceCodecCaps
import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.StreamPreferences
import com.torve.domain.repository.AddonRepository
import com.torve.domain.repository.DownloadRepository
import com.torve.domain.repository.StreamRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

data class BulkDownloadProgress(
    val isActive: Boolean = false,
    val totalEpisodes: Int = 0,
    val completedEpisodes: Int = 0,
    val currentEpisodeLabel: String = "",
    val groupId: String? = null,
)

data class EpisodeTarget(val seasonNumber: Int, val episodeNumber: Int)

class BulkDownloadManager(
    private val streamRepo: StreamRepository,
    private val streamSelector: StreamSelector,
    private val addonRepo: AddonRepository,
    private val downloadRepo: DownloadRepository,
) {
    private val _progress = MutableStateFlow(BulkDownloadProgress())
    val progress: StateFlow<BulkDownloadProgress> = _progress.asStateFlow()

    /**
     * Resolves and enqueues a download for each episode target sequentially.
     * Returns the list of successfully enqueued download IDs.
     */
    suspend fun enqueueBulk(
        mediaItem: MediaItem,
        episodes: List<EpisodeTarget>,
        debridProvider: DebridServiceType,
        debridApiKey: String,
        debridAccounts: Map<DebridServiceType, String>,
        preferences: StreamPreferences,
        deviceCaps: DeviceCodecCaps,
    ): List<String> {
        val groupId = "bulk_${Clock.System.now().toEpochMilliseconds()}"
        val enqueuedIds = mutableListOf<String>()
        val addons = addonRepo.getInstalledAddons()
        val imdbId = mediaItem.imdbId ?: return emptyList()
        val existingKeys = downloadRepo.getAllDownloads()
            .filter { it.mediaId == mediaItem.id && it.status != DownloadStatus.FAILED }
            .mapNotNull { existing ->
                val seasonNumber = existing.seasonNumber ?: return@mapNotNull null
                val episodeNumber = existing.episodeNumber ?: return@mapNotNull null
                seasonNumber to episodeNumber
            }
            .toSet()

        _progress.value = BulkDownloadProgress(
            isActive = true,
            totalEpisodes = episodes.size,
            completedEpisodes = 0,
            currentEpisodeLabel = "",
            groupId = groupId,
        )

        for ((index, target) in episodes.withIndex()) {
            val label = "S${target.seasonNumber.toString().padStart(2, '0')}E${target.episodeNumber.toString().padStart(2, '0')}"
            _progress.value = _progress.value.copy(
                completedEpisodes = index,
                currentEpisodeLabel = label,
            )

            if ((target.seasonNumber to target.episodeNumber) in existingKeys) {
                continue
            }

            try {
                // 1. Fetch streams for this specific episode
                val streams = streamRepo.fetchStreams(
                    type = mediaItem.type,
                    imdbId = imdbId,
                    season = target.seasonNumber,
                    episode = target.episodeNumber,
                    addons = addons,
                    debridAccounts = debridAccounts,
                    preferences = preferences,
                )
                if (streams.isEmpty()) continue

                // 2. Pick the best stream
                val best = streamSelector.selectBestPlayableVariant(
                    streams = streams,
                    preferences = preferences,
                    deviceCaps = deviceCaps,
                ) ?: streams.first()

                // 3. Resolve via debrid
                val resolved = streamRepo.resolveStream(
                    stream = best,
                    provider = debridProvider,
                    apiKey = debridApiKey,
                )

                // 4. Extract download URL
                val downloadUrl = resolved.transcodeUrls?.mp4
                    ?: resolved.transcodeUrls?.hls
                    ?: resolved.url

                // 5. Enqueue download
                val downloadId = "${mediaItem.id}_s${target.seasonNumber}_e${target.episodeNumber}_${Clock.System.now().toEpochMilliseconds()}"
                val download = Download(
                    id = downloadId,
                    mediaId = mediaItem.id,
                    mediaType = mediaItem.type,
                    title = "${mediaItem.title} $label",
                    posterUrl = mediaItem.posterUrl,
                    streamUrl = downloadUrl,
                    status = DownloadStatus.PENDING,
                    seasonNumber = target.seasonNumber,
                    episodeNumber = target.episodeNumber,
                    bulkGroupId = groupId,
                )
                downloadRepo.enqueueDownload(download)
                enqueuedIds.add(downloadId)
            } catch (e: Exception) {
                // Per-episode error isolation — continue with remaining episodes
            }
        }

        _progress.value = _progress.value.copy(
            isActive = false,
            completedEpisodes = episodes.size,
            currentEpisodeLabel = "",
        )

        return enqueuedIds
    }

    fun buildSeasonTargets(seasonNumber: Int, episodeCount: Int): List<EpisodeTarget> {
        return (1..episodeCount).map { ep -> EpisodeTarget(seasonNumber, ep) }
    }

    fun buildAllSeasonsTargets(mediaItem: MediaItem): List<EpisodeTarget> {
        return mediaItem.seasons
            .filter { it.seasonNumber > 0 }
            .flatMap { season ->
                (1..season.episodeCount).map { ep ->
                    EpisodeTarget(season.seasonNumber, ep)
                }
            }
    }
}
