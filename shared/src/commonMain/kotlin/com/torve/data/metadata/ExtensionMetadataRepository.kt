package com.torve.data.metadata

import com.torve.data.addon.*
import com.torve.domain.model.*
import com.torve.domain.player.MediaIdentityFactory
import com.torve.domain.repository.*
import com.torve.presentation.apple.AppleCatalogueDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

/** No provider, credentials, public catalogue or enrichment fallback is built into this repository. */
class ExtensionMetadataRepository(
    private val addons: AddonRepository,
    private val client: StremioAddonClient,
    private val owner: () -> String,
    private val language: () -> String,
) : MetadataRepository {
    private data class Entry(val item: MediaItem, val at: Long)
    private val cache = linkedMapOf<String, Entry>()
    private val lock = Mutex()
    // One title shares one bounded request. Network work never owns the cache lock,
    // so a slow extension cannot stall another page or an already cached title.
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = mutableMapOf<String, Deferred<MediaItem?>>()
    private data class RatingEntry(val ratings: MediaRatings?, val at: Long)
    private val ratingCache = linkedMapOf<String, RatingEntry>()
    private val ratingRequests = mutableMapOf<String, Deferred<MediaRatings?>>()
    private suspend fun context(locale: String): Pair<String, List<InstalledAddon>> {
        val identity = owner()
        val installed = addons.getEnabledAddons().filter { it.isEnabled }.sortedBy { it.priority }
        return (identity + "\n" + locale + "\n" + installed.joinToString("\n") {
            "${it.manifestUrl}:${it.manifest.version}:${it.priority}"
        }) to installed
    }

    private suspend fun ratingValues(item: MediaItem, scope: String, installed: List<InstalledAddon>): MediaRatings? {
        val id = listOfNotNull(item.imdbId, item.id).firstOrNull { it.matches(Regex("tt[0-9]+")) } ?: return null
        if (installed.none { it.isEnabled && ExtensionStreamRatings.supports(it.manifest) }) return null
        val key = "$scope\nratings:${item.type}:$id"
        val requestContext = currentCoroutineContext().minusKey(Job)
        val pending = lock.withLock {
            ratingCache[key]?.takeIf { Clock.System.now().toEpochMilliseconds() - it.at <
                (if (it.ratings == null) 60_000 else 3_600_000) }?.let {
                return@withLock CompletableDeferred(it.ratings)
            }
            ratingRequests.getOrPut(key) {
                requests.async(context = requestContext) {
                    try {
                        val result = ExtensionStreamRatings.enrich(item.copy(ratings = null), installed) { addon, type, imdb ->
                            client.fetchRatingStreams(addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'), type, imdb)
                        }.ratings
                        lock.withLock {
                            ratingCache[key] = RatingEntry(result, Clock.System.now().toEpochMilliseconds())
                            while (ratingCache.size > 256) ratingCache.remove(ratingCache.keys.first())
                        }
                        result
                    } finally { lock.withLock { ratingRequests.remove(key) } }
                }
            }
        }
        return pending.await()
    }

    /** Visible cards with an IMDb ID need only the rating extension, not another full metadata fetch. */
    suspend fun ratings(seed: MediaItem, locale: String = language()): MediaItem = withContext(Dispatchers.Default) {
        if (listOfNotNull(seed.imdbId, seed.id).none { it.matches(Regex("tt[0-9]+")) }) return@withContext resolve(seed, locale)
        val (scope, installed) = context(locale)
        val value = ratingValues(seed, scope, installed) ?: return@withContext resolve(seed, locale)
        if (context(locale).first != scope) return@withContext seed
        seed.copy(ratings = (seed.ratings ?: MediaRatings()).copy(imdbScore = value.imdbScore, imdbVotes = value.imdbVotes))
    }

    suspend fun resolve(seed: MediaItem, locale: String = language()): MediaItem = withContext(Dispatchers.Default) {
        val (scope, installed) = context(locale)
        val detailScope = scope + "\nsource=" + seed.metadataSourceKey.orEmpty()
        val key = "$detailScope\n${seed.type}:${seed.id}"
        val requestContext = currentCoroutineContext().minusKey(Job)
        val pending = lock.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            cache[key]?.takeIf { now - it.at < 15 * 60_000 }?.let {
                return@withLock CompletableDeferred<MediaItem?>(it.item.copy(id = seed.id))
            }
            inFlight.getOrPut(key) {
                requests.async(context = requestContext) {
                    try {
                        val item = withTimeoutOrNull(30_000) {
                            val detail = AppleCatalogueDetail.resolve(seed, installed,
                                fetchMeta = { addon, type, id -> withTimeoutOrNull(10_000) {
                                    client.fetchMeta(addon.manifestUrl.removeSuffix("/manifest.json").trimEnd('/'), type, id).meta
                                } }, enrich = { null }, extensionAuthoritative = true)
                            ratingValues(detail, scope, installed)?.let { value -> detail.copy(
                                ratings = (detail.ratings ?: MediaRatings()).copy(imdbScore = value.imdbScore, imdbVotes = value.imdbVotes)) } ?: detail
                        } ?: seed
                        if (context(locale).first != scope) return@async null
                        if (item != seed) lock.withLock {
                            val completed = Clock.System.now().toEpochMilliseconds()
                            val aliases = listOfNotNull(seed.id, item.id, item.imdbId, item.tmdbId?.toString(), item.tmdbId?.let { "tmdb:$it" })
                            for (id in aliases) cache["$detailScope\n${seed.type}:$id"] = Entry(item.copy(id = seed.id), completed)
                            while (cache.size > 64) cache.remove(cache.keys.first())
                        }
                        item.takeIf { it != seed }
                    } finally { lock.withLock { inFlight.remove(key) } }
                }
            }
        }
        val item = pending.await()
        // Revalidate even a cache hit: a profile/configuration may change while a caller waits.
        if (context(locale).first == scope && item != null) AppleCatalogueDetail.fillMissing(item, seed).copy(id = seed.id) else seed
    }

    suspend fun presentation(id: String, type: String, locale: String = language()): MediaItem? {
        val mediaType = if (type in setOf("tv", "series")) MediaType.SERIES else MediaType.MOVIE
        val numeric = id.removePrefix("tmdb:").toIntOrNull()?.takeIf { it > 0 }
        val seed = MediaItem(id = id, type = mediaType, title = "", tmdbId = numeric,
            imdbId = id.takeIf { it.matches(Regex("tt[0-9]+")) })
        return resolve(seed, locale).takeIf { it.title.isNotBlank() }
    }
    suspend fun cached(ids: List<String>, type: String, locale: String): List<MediaItem> {
        val scope = context(locale).first
        val mediaType = if (type in setOf("tv", "series")) MediaType.SERIES else MediaType.MOVIE
        val now = Clock.System.now().toEpochMilliseconds()
        val items = lock.withLock { ids.mapNotNull { id -> cache["$scope\nsource=\n$mediaType:$id"]?.takeIf { now - it.at < 15 * 60_000 }?.item?.copy(id = id) } }
        return if (context(locale).first == scope) items else emptyList()
    }
    override suspend fun getDetail(type: String, id: Int): MediaItem = presentation("tmdb:$id", type)
        ?: throw IllegalStateException("Install a metadata extension that supports this title in Extensions.")
    override suspend fun getPresentation(type: String, id: Int, language: String?): MediaItem =
        presentation("tmdb:$id", type, language ?: this.language())
            ?: throw IllegalStateException("The installed extensions did not return metadata for this title.")
    override suspend fun cachedPresentations(type: String, ids: List<Int>, language: String) = cached(ids.map(Int::toString), type, language)
    override suspend fun findByImdbId(imdbId: String, preferredType: String?): MediaItem? =
        presentation(imdbId, preferredType ?: "movie") ?: if (preferredType == null) presentation(imdbId, "tv") else null
    override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season =
        getDetail("tv", tvId).seasons.firstOrNull { it.seasonNumber == seasonNumber } ?: Season(seasonNumber, 0)
    override suspend fun getLogoUrl(type: String, tmdbId: Int) = presentation("tmdb:$tmdbId", type)?.logoUrl
    override suspend fun getMediaImageUrls(type: String, id: Int): List<String> = presentation("tmdb:$id", type)?.let {
        listOfNotNull(it.posterUrl, it.backdropUrl).distinct()
    }.orEmpty()

    override suspend fun searchMulti(query: String, page: Int) = searchMultiPaged(query, page).items
    override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult {
        if (query.trim().length < 2) return empty(page)
        val (scope, installed) = context(language())
        val result = mutableListOf<MediaItem>()
        var hasMore = false
        for (addon in installed.take(8)) for (catalog in addon.manifest.catalogs.filter {
            it.extra.any { extra -> extra.name == "search" } &&
                (type == null || it.type == if (type == "tv") "series" else type)
        }.take(2)) {
            val paged = catalog.extra.any { it.name == "skip" }
            if (page > 1 && !paged) continue
            val rows = try { withTimeoutOrNull(10_000) {
                client.fetchCatalog(addon.manifestUrl.removeSuffix("/manifest.json"), catalog.type, catalog.id,
                    search = query.trim(), skip = if (paged) (page - 1) * 100 else null).metas
            }.orEmpty() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            result += rows.map { it.copy(type = it.type.ifBlank { catalog.type }).toCatalogueMediaItem(addon.manifest.id).copy(metadataSourceKey = MediaIdentityFactory.stableHash(addon.manifestUrl)) }
            hasMore = hasMore || (paged && rows.isNotEmpty())
        }
        if (context(language()).first != scope) return empty(page)
        val items = result.distinctBy { "${it.type}:${it.id}" }
        return PagedResult(items, page, if (hasMore) page + 1 else page, items.size)
    }

    private fun empty(page: Int) = PagedResult(emptyList(), page, page, 0)
    override suspend fun getTrending(type: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun getPopular(type: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun getTopRated(type: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun getUpcoming(page: Int): List<MediaItem> = emptyList()
    override suspend fun getNowPlaying(page: Int): List<MediaItem> = emptyList()
    override suspend fun getAiringToday(page: Int): List<MediaItem> = emptyList()
    override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
    override suspend fun getRecommendations(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
    override suspend fun getHomeShelves(): List<CatalogShelf> = emptyList()
    override suspend fun getPersonCredits(personId: Int): List<MediaItem> = emptyList()
    override suspend fun getPersonDetail(personId: Int) = TmdbPerson(personId)
    override suspend fun getTrendingPaged(type: String, page: Int) = empty(page)
    override suspend fun getPopularPaged(type: String, page: Int) = empty(page)
    override suspend fun getTopRatedPaged(type: String, page: Int) = empty(page)
    override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
    override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
    override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
    override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
    override suspend fun discover(type: String, page: Int, sortBy: String, withGenres: String?, minRating: Float?,
        year: Int?, yearTo: Int?, runtimeGte: Int?, runtimeLte: Int?, originCountries: String?, originalLanguage: String?,
        certification: String?, certificationGte: String?, certificationLte: String?, certificationCountry: String?,
        withCast: String?, withCrew: String?, withWatchProviders: String?, watchRegion: String?, withKeywords: String?,
        withCompanies: String?) = empty(page)
}
