package com.torve.data.subtitles

import com.torve.data.addon.MediaReleaseFingerprint
import com.torve.data.addon.SourceContinuationSessionStore
import com.torve.data.addon.SubtitleAggregator
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SubtitleDiscoveryRequest(
    val contentId: String,
    val imdbId: String,
    val mediaType: MediaType,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val languages: Set<String> = emptySet(),
    val fingerprint: MediaReleaseFingerprint,
    val playbackUrl: String? = null,
    val addons: List<InstalledAddon> = emptyList(),
)

data class SubtitleDiscoveryResult(
    val fingerprint: MediaReleaseFingerprint,
    val ranked: List<RankedSubtitle>,
    val movieHashAvailable: Boolean,
    val fromCache: Boolean,
    val hasStrongMatch: Boolean,
)

/** One discovery path for OpenSubtitles and Stremio providers, followed by one canonical ranker. */
class SubtitleDiscoveryService(
    private val openSubtitlesClient: OpenSubtitlesClient,
    private val subtitleAggregator: SubtitleAggregator,
    private val hashService: OpenSubtitlesHashService,
    private val intelligence: SubtitleIntelligence,
    private val cache: SubtitleSearchCache,
) {
    suspend fun discover(request: SubtitleDiscoveryRequest): SubtitleDiscoveryResult = coroutineScope {
        val languageScope = request.languages.map(String::lowercase).sorted().joinToString(",")
        val initialKey = cacheKey(request, request.fingerprint, languageScope)
        cache.get(initialKey)?.let { cached ->
            return@coroutineScope result(request.fingerprint, cached, fromCache = true)
        }

        val addonResults = async {
            runCatching {
                subtitleAggregator.fetchSubtitles(
                    addons = request.addons,
                    type = request.mediaType,
                    imdbId = request.imdbId,
                    season = request.seasonNumber,
                    episode = request.episodeNumber,
                    addonTimeoutMs = 7_000L,
                )
            }.getOrDefault(emptyList())
        }
        val movieHashDeferred = async {
            val url = request.playbackUrl ?: return@async null
            hashService.calculateForHttp(url, request.fingerprint.sizeBytes)
        }
        val openSubtitlesConfigured = openSubtitlesClient.isConfiguredAsync()
        val languageParameter = languageScope.takeIf(String::isNotBlank)
        val genericResults = if (openSubtitlesConfigured) async {
            openSubtitlesClient.searchSubtitles(
                imdbId = request.imdbId,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber,
                languages = languageParameter,
            )
        } else null
        val releaseResults = if (openSubtitlesConfigured) {
            request.fingerprint.releaseName?.takeIf(String::isNotBlank)?.let { release ->
                async {
                    openSubtitlesClient.searchSubtitles(
                        imdbId = request.imdbId,
                        seasonNumber = request.seasonNumber,
                        episodeNumber = request.episodeNumber,
                        languages = languageParameter,
                        releaseQuery = release,
                    )
                }
            }
        } else null
        val movieHash = movieHashDeferred.await()
        val activeFingerprint = movieHash?.let { hash ->
            SourceContinuationSessionStore.session.recordMovieHash(hash.hash, hash.fileSize)
                ?: request.fingerprint.copy(
                    movieHash = hash.hash,
                    movieHashAvailable = true,
                    sizeBytes = request.fingerprint.sizeBytes ?: hash.fileSize,
                )
        } ?: request.fingerprint.takeIf { it.movieHashAvailable && it.movieHash != null }
            ?: request.fingerprint.copy(movieHash = null, movieHashAvailable = false)

        val openSubtitlesResults = if (openSubtitlesConfigured) {
            val hashSpecific = activeFingerprint.movieHash?.let { hash ->
                activeFingerprint.sizeBytes?.let { size ->
                async {
                    openSubtitlesClient.searchSubtitles(
                        imdbId = request.imdbId,
                        seasonNumber = request.seasonNumber,
                        episodeNumber = request.episodeNumber,
                        languages = languageParameter,
                        movieHash = hash,
                        movieByteSize = size,
                    )
                }
                }
            }
            // Hash results come first so deduplication can never discard an
            // explicit moviehash_match in favor of the generic response.
            (hashSpecific?.await().orEmpty() + releaseResults?.await().orEmpty() + genericResults?.await().orEmpty())
                .distinctBy { it.fileId }
        } else {
            emptyList()
        }

        val providerNeutral = openSubtitlesResults.map(::mapOpenSubtitlesResult) +
            addonResults.await().map { subtitle ->
                SubtitleMetadata(
                    subtitleId = subtitle.id,
                    subtitleFileId = null,
                    provider = subtitle.provider ?: "Stremio addon",
                    language = subtitle.lang,
                    subtitleFilename = subtitle.label ?: subtitle.id,
                    releaseName = subtitle.label ?: subtitle.id,
                    fps = null,
                    rating = null,
                    voteCount = null,
                    downloadCount = null,
                    recentDownloadCount = null,
                    trustedUploader = null,
                    uploaderName = null,
                    uploaderRank = null,
                    hearingImpaired = null,
                    forced = null,
                    hd = null,
                    aiTranslated = null,
                    machineTranslated = null,
                    uploadDate = null,
                    movieHashMatch = null,
                    directUrl = subtitle.url,
                    mimeType = null,
                )
            }
        val ranked = intelligence.rank(
            fingerprint = activeFingerprint,
            candidates = providerNeutral.distinctBy { candidate ->
                candidate.subtitleFileId?.let { "os:$it" }
                    ?: candidate.directUrl
                    ?: "${candidate.provider}:${candidate.subtitleId}:${candidate.releaseName}:${candidate.language}"
            },
            requestedSeason = request.seasonNumber,
            requestedEpisode = request.episodeNumber,
        )
        val finalKey = cacheKey(request, activeFingerprint, languageScope)
        cache.put(finalKey, ranked)
        result(activeFingerprint, ranked, fromCache = false)
    }

    private fun result(
        fingerprint: MediaReleaseFingerprint,
        ranked: List<RankedSubtitle>,
        fromCache: Boolean,
    ) = SubtitleDiscoveryResult(
        fingerprint = fingerprint,
        ranked = ranked,
        movieHashAvailable = fingerprint.movieHashAvailable,
        fromCache = fromCache,
        hasStrongMatch = ranked.any { it.tier.priority <= SubtitleMatchTier.STRONG_RELEASE_MATCH.priority },
    )

    private fun cacheKey(
        request: SubtitleDiscoveryRequest,
        fingerprint: MediaReleaseFingerprint,
        languageScope: String,
    ) = SubtitleCacheKey(
        contentId = request.contentId,
        seasonNumber = request.seasonNumber,
        episodeNumber = request.episodeNumber,
        languageScope = languageScope,
        releaseFingerprint = fingerprint.subtitleCacheFingerprint(),
    )
}

private fun mapOpenSubtitlesResult(result: OsSubtitleResult): SubtitleMetadata = SubtitleMetadata(
    subtitleId = result.subtitleId,
    subtitleFileId = result.fileId,
    provider = "OpenSubtitles.com",
    language = result.language,
    subtitleFilename = result.fileName,
    releaseName = result.release.takeIf(String::isNotBlank) ?: result.fileName,
    fps = result.fps,
    rating = result.ratings,
    voteCount = result.voteCount,
    downloadCount = result.downloadCount,
    recentDownloadCount = result.recentDownloadCount,
    trustedUploader = result.fromTrusted,
    uploaderName = result.uploaderName,
    uploaderRank = result.uploaderRank,
    hearingImpaired = result.hearingImpaired,
    forced = result.forced,
    hd = result.hd,
    aiTranslated = result.aiTranslated,
    machineTranslated = result.machineTranslated,
    uploadDate = result.uploadDate,
    movieHashMatch = result.movieHashMatch,
    comments = result.comments,
    subtitleFileCount = result.numberOfCds,
    format = result.fileName.substringAfterLast('.', missingDelimiterValue = "").takeIf(String::isNotBlank),
    mediaTitle = result.mediaTitle,
    mediaYear = result.mediaYear,
    seasonNumber = result.seasonNumber,
    episodeNumber = result.episodeNumber,
)
