package com.torve.data.subtitles

import com.torve.data.addon.MediaReleaseFingerprint
import com.torve.data.addon.SourceContinuationSessionStore
import com.torve.data.addon.SubtitleAggregator
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class SubtitleDiscoveryRequest(
    val contentId: String,
    val imdbId: String,
    val mediaType: MediaType,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val languages: Set<String> = emptySet(),
    val preferredLanguage: String? = null,
    val fingerprint: MediaReleaseFingerprint,
    /** Authoritative catalog title (series title for episodes), never an episode title or resolver label. */
    val contentTitle: String? = null,
    val playbackUrl: String? = null,
    val addons: List<InstalledAddon> = emptyList(),
    val openSubtitlesPageLimit: Int = INITIAL_PAGE_LIMIT,
    val forceRefresh: Boolean = false,
)

data class SubtitleProviderReport(
    val provider: String,
    val configured: Boolean,
    val returnedCount: Int,
    val totalAvailable: Int? = null,
    val pagesLoaded: Int = 0,
    val status: String,
)

data class SubtitleDiscoveryResult(
    val fingerprint: MediaReleaseFingerprint,
    val ranked: List<RankedSubtitle>,
    val movieHashAvailable: Boolean,
    val fromCache: Boolean,
    val hasStrongMatch: Boolean,
    val providerReports: List<SubtitleProviderReport>,
    val openSubtitlesPageLimit: Int,
    val canLoadMore: Boolean,
)

private data class OpenSubtitleBatch(
    val subtitles: List<OsSubtitleResult> = emptyList(),
    val totalCount: Int? = null,
    val totalPages: Int? = null,
    val pagesLoaded: Int = 0,
    val failure: String? = null,
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
        val pageLimit = request.openSubtitlesPageLimit.coerceIn(1, MAX_PAGE_LIMIT)
        val providerLanguageScope = request.languages.mapNotNull(::normalizeSubtitleLanguageCode).sorted().joinToString(",")
        val preferredLanguageCode = normalizeSubtitleLanguageCode(request.preferredLanguage)
        val languageScope = "$providerLanguageScope|preferred:${preferredLanguageCode.orEmpty()}"
        val catalogTitle = normalizeMediaTitle(request.contentTitle)
        val requestedFingerprint = request.fingerprint.copy(
            parsedTitle = catalogTitle ?: request.fingerprint.parsedTitle,
        )
        val initialKey = cacheKey(request, requestedFingerprint, languageScope, pageLimit)
        if (!request.forceRefresh) cache.get(initialKey)?.let { cached ->
            val reports = reportsFromCached(cached)
            return@coroutineScope result(
                fingerprint = requestedFingerprint,
                ranked = cached,
                fromCache = true,
                providerReports = reports,
                pageLimit = pageLimit,
                canLoadMore = pageLimit < MAX_PAGE_LIMIT,
            )
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
        val languageParameter = providerLanguageScope.takeIf(String::isNotBlank)
        val genericResults = if (openSubtitlesConfigured) async {
            searchOpenSubtitlesPages(
                imdbId = request.imdbId,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber,
                languages = languageParameter,
                pageLimit = pageLimit,
            )
        } else null
        val preferredResults = if (
            openSubtitlesConfigured &&
            providerLanguageScope.isBlank() &&
            !preferredLanguageCode.isNullOrBlank()
        ) async {
            // OpenSubtitles result pages are multilingual. Fetching the user's
            // preferred language independently prevents valid preferred results
            // from being crowded out before Torve's bounded pagination ends.
            searchOpenSubtitlesPages(
                imdbId = request.imdbId,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber,
                languages = preferredLanguageCode,
                pageLimit = pageLimit,
            )
        } else null
        val releaseResults = if (openSubtitlesConfigured) {
            request.fingerprint.releaseName?.takeIf(String::isNotBlank)?.let { release ->
                async {
                    searchOpenSubtitlesPages(
                        imdbId = request.imdbId,
                        seasonNumber = request.seasonNumber,
                        episodeNumber = request.episodeNumber,
                        languages = languageParameter,
                        releaseQuery = release,
                        pageLimit = 1,
                    )
                }
            }
        } else null
        val titleResults = if (openSubtitlesConfigured && !request.contentTitle.isNullOrBlank()) async {
            // A title-only fallback covers provider records that are not linked to the expected IMDb ID.
            // The ranker does not trust this query as identity proof and still rejects wrong episodes.
            searchOpenSubtitlesPages(
                imdbId = null,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber,
                languages = languageParameter,
                releaseQuery = request.contentTitle,
                pageLimit = 1,
            )
        } else null
        val movieHash = movieHashDeferred.await()
        val hashedFingerprint = movieHash?.let { hash ->
            SourceContinuationSessionStore.session.recordMovieHash(hash.hash, hash.fileSize)
                ?: requestedFingerprint.copy(
                    movieHash = hash.hash,
                    movieHashAvailable = true,
                    sizeBytes = requestedFingerprint.sizeBytes ?: hash.fileSize,
                )
        } ?: requestedFingerprint.takeIf { it.movieHashAvailable && it.movieHash != null }
            ?: requestedFingerprint.copy(movieHash = null, movieHashAvailable = false)
        val activeFingerprint = hashedFingerprint.copy(
            parsedTitle = catalogTitle ?: hashedFingerprint.parsedTitle,
        )

        val hashResults = if (openSubtitlesConfigured) {
            val hashSpecific = activeFingerprint.movieHash?.let { hash ->
                activeFingerprint.sizeBytes?.let { size ->
                async {
                    searchOpenSubtitlesPages(
                        imdbId = request.imdbId,
                        seasonNumber = request.seasonNumber,
                        episodeNumber = request.episodeNumber,
                        languages = languageParameter,
                        movieHash = hash,
                        movieByteSize = size,
                        pageLimit = 1,
                    )
                }
                }
            }
            hashSpecific?.await() ?: OpenSubtitleBatch()
        } else {
            OpenSubtitleBatch()
        }
        val releaseBatch = releaseResults?.await() ?: OpenSubtitleBatch()
        val preferredBatch = preferredResults?.await() ?: OpenSubtitleBatch()
        val genericBatch = genericResults?.await() ?: OpenSubtitleBatch()
        val titleBatch = titleResults?.await() ?: OpenSubtitleBatch()

        // Hash results come first so deduplication can never discard an explicit
        // moviehash_match in favor of a generic copy of the same file.
        val openSubtitlesNeutral = (
            hashResults.subtitles.map { mapOpenSubtitlesResult(it, identityMatchedByRequest = true) } +
                releaseBatch.subtitles.map { mapOpenSubtitlesResult(it, identityMatchedByRequest = true) } +
                preferredBatch.subtitles.map { mapOpenSubtitlesResult(it, identityMatchedByRequest = true) } +
                genericBatch.subtitles.map { mapOpenSubtitlesResult(it, identityMatchedByRequest = true) } +
                titleBatch.subtitles.map { mapOpenSubtitlesResult(it, identityMatchedByRequest = false) }
            ).distinctBy { it.subtitleFileId }
        val addonSubtitles = addonResults.await()
        val providerNeutral = openSubtitlesNeutral + addonSubtitles.map { subtitle ->
                val humanLabel = humanReadableSubtitleName(subtitle.label)
                SubtitleMetadata(
                    subtitleId = subtitle.id,
                    subtitleFileId = null,
                    provider = subtitle.provider ?: "Stremio addon",
                    language = subtitle.lang,
                    subtitleFilename = humanLabel,
                    releaseName = humanLabel,
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
                    identityMatchedByRequest = true,
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
            expectedImdbId = request.imdbId,
            isSeries = request.mediaType == MediaType.SERIES,
            preferredLanguage = request.preferredLanguage,
        )
        val failures = listOfNotNull(
            hashResults.failure,
            releaseBatch.failure,
            preferredBatch.failure,
            genericBatch.failure,
            titleBatch.failure,
        ).distinct()
        val totalAvailable = genericBatch.totalCount?.takeIf { it > 0 }
        val openStatus = when {
            !openSubtitlesConfigured -> "Not configured"
            openSubtitlesNeutral.isNotEmpty() && failures.isNotEmpty() -> "Partial results (${failures.joinToString()})"
            openSubtitlesNeutral.isNotEmpty() -> "Loaded ${openSubtitlesNeutral.size}${totalAvailable?.let { " (catalog total $it)" }.orEmpty()}"
            failures.isNotEmpty() -> failures.joinToString()
            else -> "No results returned"
        }
        val providerReports = listOf(
            SubtitleProviderReport(
                provider = "OpenSubtitles.com",
                configured = openSubtitlesConfigured,
                returnedCount = openSubtitlesNeutral.size,
                totalAvailable = totalAvailable,
                pagesLoaded = genericBatch.pagesLoaded,
                status = openStatus,
            ),
            SubtitleProviderReport(
                provider = "Subtitle addons",
                configured = request.addons.any { addon ->
                    addon.isEnabled && addon.manifest.resources.any { it == "subtitles" }
                },
                returnedCount = addonSubtitles.size,
                status = when {
                    request.addons.none { addon -> addon.isEnabled && addon.manifest.resources.any { it == "subtitles" } } ->
                        "Not configured"
                    addonSubtitles.isEmpty() -> "No results returned"
                    else -> "Loaded ${addonSubtitles.size}"
                },
            ),
        )
        val canLoadMore = openSubtitlesConfigured && pageLimit < MAX_PAGE_LIMIT &&
            (genericBatch.totalPages?.let { pageLimit < it } ?: genericBatch.subtitles.isNotEmpty())
        val finalKey = cacheKey(request, activeFingerprint, languageScope, pageLimit)
        // Empty responses and total provider failures remain retryable; never make them sticky for ten minutes.
        if (ranked.isNotEmpty()) cache.put(finalKey, ranked)
        result(activeFingerprint, ranked, false, providerReports, pageLimit, canLoadMore)
    }

    private suspend fun searchOpenSubtitlesPages(
        imdbId: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        languages: String?,
        releaseQuery: String? = null,
        movieHash: String? = null,
        movieByteSize: Long? = null,
        pageLimit: Int,
    ): OpenSubtitleBatch = coroutineScope {
        val first = openSubtitlesClient.searchSubtitlesPage(
            imdbId, seasonNumber, episodeNumber, languages, releaseQuery, movieHash, movieByteSize, page = 1,
        )
        if (first.failure != null) return@coroutineScope OpenSubtitleBatch(failure = first.failure)
        val finalPage = minOf(pageLimit, first.totalPages ?: pageLimit).coerceAtLeast(1)
        val remaining = (2..finalPage).map { page ->
            async {
                openSubtitlesClient.searchSubtitlesPage(
                    imdbId, seasonNumber, episodeNumber, languages, releaseQuery, movieHash, movieByteSize, page,
                )
            }
        }.awaitAll()
        val pages = listOf(first) + remaining
        OpenSubtitleBatch(
            subtitles = pages.flatMap { it.subtitles }.distinctBy { it.fileId },
            totalCount = first.totalCount,
            totalPages = first.totalPages,
            pagesLoaded = pages.count { it.failure == null },
            failure = pages.mapNotNull { it.failure }.distinct().takeIf { it.isNotEmpty() }?.joinToString(),
        )
    }

    private fun result(
        fingerprint: MediaReleaseFingerprint,
        ranked: List<RankedSubtitle>,
        fromCache: Boolean,
        providerReports: List<SubtitleProviderReport>,
        pageLimit: Int,
        canLoadMore: Boolean,
    ) = SubtitleDiscoveryResult(
        fingerprint = fingerprint,
        ranked = ranked,
        movieHashAvailable = fingerprint.movieHashAvailable,
        fromCache = fromCache,
        hasStrongMatch = ranked.any { it.tier.priority <= SubtitleMatchTier.STRONG_RELEASE_MATCH.priority },
        providerReports = providerReports,
        openSubtitlesPageLimit = pageLimit,
        canLoadMore = canLoadMore,
    )

    private fun reportsFromCached(ranked: List<RankedSubtitle>): List<SubtitleProviderReport> = ranked
        .groupingBy { it.subtitle.provider }
        .eachCount()
        .map { (provider, count) -> SubtitleProviderReport(provider, true, count, status = "Loaded $count (cached)") }

    private fun cacheKey(
        request: SubtitleDiscoveryRequest,
        fingerprint: MediaReleaseFingerprint,
        languageScope: String,
        pageLimit: Int,
    ) = SubtitleCacheKey(
        contentId = request.contentId,
        seasonNumber = request.seasonNumber,
        episodeNumber = request.episodeNumber,
        languageScope = "$languageScope|pages:$pageLimit",
        releaseFingerprint = fingerprint.subtitleCacheFingerprint(),
    )
}

private fun mapOpenSubtitlesResult(
    result: OsSubtitleResult,
    identityMatchedByRequest: Boolean,
): SubtitleMetadata = SubtitleMetadata(
    subtitleId = result.subtitleId,
    subtitleFileId = result.fileId,
    provider = "OpenSubtitles.com",
    language = result.language,
    subtitleFilename = humanReadableSubtitleName(result.fileName),
    releaseName = humanReadableSubtitleName(result.release) ?: humanReadableSubtitleName(result.fileName),
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
    mediaImdbId = result.mediaImdbId,
    parentImdbId = result.parentImdbId,
    identityMatchedByRequest = identityMatchedByRequest,
)

const val INITIAL_PAGE_LIMIT = 2
const val MAX_PAGE_LIMIT = 10
