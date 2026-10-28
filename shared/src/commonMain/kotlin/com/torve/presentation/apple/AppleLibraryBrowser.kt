package com.torve.presentation.apple

import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.data.debrid.DebridClient
import com.torve.data.panda.NzbIndexerRow
import com.torve.data.usenet.*
import com.torve.domain.integrations.*
import com.torve.domain.model.*
import com.torve.domain.nzb.BackendNzbResolver
import com.torve.domain.repository.*
import com.torve.domain.sports.*
import com.torve.domain.usenet.UsenetIndexerUrlResolver
import com.torve.presentation.panda.PandaConfigStateStore
import com.torve.util.DisplayTextDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.torve.data.mdblist.RatingsEnricher
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.Koin

@Serializable data class AppleBrowseRow(
    val id: String, val title: String, val subtitle: String = "", val artwork: String? = null,
    val mediaId: String = "", val mediaType: String = "movie", val season: Int = 0, val episode: Int = 0,
    val kind: String = "detail", val size: Long = 0, val time: Long = 0, val ready: Boolean = true,
    val requestId: Int = 0, val canRetry: Boolean = false, val canDelete: Boolean = false, val year: Int? = null,
    val ratings: List<AppleTvRating> = emptyList(), val backdrop: String? = null,
)
@Serializable data class AppleBrowsePage(val title: String, val items: List<AppleBrowseRow>, val notice: String? = null, val setup: String? = null, val error: String? = null)

/** Native destinations share the same services as Android. Stream and NZB URLs stay out of list state. */
class AppleLibraryBrowser(private val koin: Koin) {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true }
    private var loadedOwner: String? = null
    private var sports = emptyMap<String, NewznabItem>()
    private var visible = emptyList<AppleBrowseRow>()
    private var destination = ""
    private var provider = "REAL_DEBRID"
    private var parent = ""
    private var cachedPage: AppleBrowsePage? = null
    private var cacheKey = ""
    private var cacheTime = 0L
    private fun owner() = koin.get<UserIdProvider>().currentDataUserId()
    private val secrets get() = koin.get<IntegrationSecretStore>()
    private fun type(value: MediaType) = if (value == MediaType.SERIES) "tv" else "movie"
    @Throws(Exception::class)
    suspend fun load(kind: String, query: String, filter: String, days: Int, sort: String, folder: String, cloudProvider: String): String = lock.withLock {
        if (kind == "sports") {
            // Retired destinations must never query saved indexers, including old links.
            return@withLock json.encodeToString(AppleBrowsePage("Library", emptyList(), notice = "This section is no longer available."))
        }
        koin.get<UserIdProvider>().withPersonalDataScope {
            val scopeOwner = owner()
            loadedOwner = null; visible = emptyList()
            destination = kind; parent = folder; provider = cloudProvider
            val key = "$scopeOwner|${policy.policyStateVersion}|${policy.isLocked}|$kind|$filter|$days|$folder|$cloudProvider"
            val page = cachedPage?.takeIf { key == cacheKey && Clock.System.now().toEpochMilliseconds() - cacheTime < 60_000 } ?: when (kind) {
                "favorites" -> favorites()
                "history" -> history()
                "requests" -> requests()
                "sports" -> sports(filter, days)
                "cloud" -> cloud(folder, cloudProvider)
                else -> error("Unknown library destination.")
            }
            check(scopeOwner == owner()) { "Your profile changed. Open the page again." }
            cachedPage = page; cacheKey = key; cacheTime = Clock.System.now().toEpochMilliseconds()
            val filtered = page.items.filter { query.isBlank() || it.title.contains(query, true) }
            visible = when (sort) {
                "NAME" -> filtered.sortedBy { it.title.lowercase() }
                "SIZE_DESC" -> filtered.sortedWith(compareBy<AppleBrowseRow> { it.size <= 0 }.thenByDescending { it.size })
                "SIZE_ASC" -> filtered.sortedWith(compareBy<AppleBrowseRow> { it.size <= 0 }.thenBy { it.size })
                else -> filtered.sortedByDescending { it.time }
            }
            loadedOwner = scopeOwner
            json.encodeToString(page.copy(items = visible))
        }
    }
    @Throws(Exception::class)
    suspend fun invalidate() = lock.withLock { cachedPage = null; cacheKey = ""; sports = emptyMap() }
    private val policy get() = koin.get<com.torve.data.contentpolicy.ContentPolicyRepository>().state.value
    private val contentFilter get() = koin.get<com.torve.presentation.contentpolicy.ContentPolicyFilter>()
    private suspend fun favorites(): AppleBrowsePage {
        val repo = koin.get<MediaFavoritesRepository>()
        repo.refreshAndAwait()
        val s = repo.state.value
        val allowed = enrichCollection(s.items.map { it.toMediaItem() })
        return AppleBrowsePage("Favorites", allowed.map { item ->
            val favorite = s.items.firstOrNull { it.toMediaItem().id == item.id && it.mediaType == item.type }
            AppleBrowseRow(item.favoriteMediaKey(), item.title, item.year?.toString().orEmpty(), item.posterUrl,
                item.id, type(item.type), year = item.year, ratings = AppleNativeFeatures(koin).ratings(item, false), time = favorite?.addedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0)
        }, s.lastError)
    }
    private suspend fun history(): AppleBrowsePage {
        val entries = AppleLibraryCollectionLayout.history(contentFilter.filterWatchHistory(policy, ContentAccessContext.LIBRARY_OR_WATCHLIST, koin.get<WatchHistoryRepository>().getAll())).take(50)
        val enriched = enrichCollection(entries.map { MediaItem(id = it.mediaId, type = MediaType.fromString(it.mediaType), title = it.showTitle ?: it.title, posterUrl = it.posterUrl, backdropUrl = it.backdropUrl) })
        return AppleBrowsePage("Viewing history", entries.mapNotNull {
        val media = enriched.firstOrNull { item -> item.id == it.mediaId && item.type == MediaType.fromString(it.mediaType) } ?: return@mapNotNull null
        AppleBrowseRow(if (it.mediaId.isBlank()) "event:${it.id}" else media.favoriteMediaKey(), it.showTitle ?: it.title,
            listOfNotNull(it.seasonNumber?.let { season -> "S$season E${it.episodeNumber ?: 1}" },
                Instant.fromEpochMilliseconds(it.watchedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()).joinToString(" · "),
            it.posterUrl, it.mediaId, if (it.mediaType.lowercase() in setOf("series", "tv")) "tv" else "movie",
            it.seasonNumber ?: 0, it.episodeNumber ?: 0, time = it.watchedAt, year = media.year, ratings = AppleNativeFeatures(koin).ratings(media, false), backdrop = it.backdropUrl)
        })
    }
    private suspend fun enrichCollection(items: List<MediaItem>): List<MediaItem> {
        val allowed = contentFilter.filterItems(policy, ContentAccessContext.LIBRARY_OR_WATCHLIST, items, ContentSourceType.LOCAL_LIBRARY).items
        val enricher = koin.get<RatingsEnricher>()
        val hydrated = enricher.hydrateListFromCache(allowed)
        val gate = Semaphore(4)
        val metadata = withTimeoutOrNull(12_000) {
            coroutineScope { hydrated.map { item -> async { gate.withPermit {
                val id = item.tmdbId ?: AppleDetailNavigation.catalogId(item.id)
                val detail = if (id == null) null else try { withTimeoutOrNull(3_000) { koin.get<MetadataRepository>().getDetail(type(item.type), id) } }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                detail?.copy(id = item.id, title = item.title, posterUrl = item.posterUrl ?: detail.posterUrl,
                    backdropUrl = item.backdropUrl ?: detail.backdropUrl, year = item.year ?: detail.year,
                    rating = detail.rating ?: item.rating, ratings = item.ratings ?: detail.ratings) ?: item
            } } }.awaitAll() }
        } ?: hydrated
        val key = secrets.get(IntegrationSecretKey.MDBLIST_API_KEY).orEmpty()
        val rated = try { withTimeoutOrNull(8_000) { enricher.enrichList(metadata, key) } ?: metadata }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { metadata }
        return contentFilter.filterItems(policy, ContentAccessContext.LIBRARY_OR_WATCHLIST, rated, ContentSourceType.LOCAL_LIBRARY).items
    }
    private suspend fun requests(): AppleBrowsePage {
        val service = koin.get<MediaLifecycleService>()
        if (!service.isConfigured()) return AppleBrowsePage("Requests", emptyList(), "Connect your request service to see requests and library availability.", "requests")
        val entries = withTimeoutOrNull(15_000) { service.listRecent(100) }
            ?: return AppleBrowsePage("Requests", emptyList(), error = "Your request service did not respond. Check its address and connection, then try again.")
        val allowed = contentFilter.filterItems(policy, ContentAccessContext.LIBRARY_OR_WATCHLIST,
            entries.map { MediaItem(id = it.status.tmdbId.toString(), type = it.status.mediaType, title = it.title, posterUrl = it.posterUrl, year = it.year, rating = it.rating) }, ContentSourceType.LOCAL_LIBRARY).items
        return AppleBrowsePage("Requests", allowed.map { item ->
            val entry = entries.first { it.status.tmdbId.toString() == item.id && it.status.mediaType == item.type }
            val status = entry.status
            AppleBrowseRow("request:${status.mediaType}:${status.tmdbId}:${status.requestId}", item.title,
                status.state.name.lowercase().replace('_', ' '), item.posterUrl, status.tmdbId.toString(), type(status.mediaType),
                requestId = status.requestId ?: 0, canRetry = status.canRetry, canDelete = status.canDeleteRequest, year = item.year)
        })
    }
    private suspend fun indexers(): List<NzbIndexerRow> {
        val stored = secrets.getSubKeys(IntegrationSecretKey.PANDA_INDEXER_API_KEY).filter { '|' in it }.mapNotNull {
            val key = secrets.get(IntegrationSecretKey.PANDA_INDEXER_API_KEY, it)?.takeIf { key -> key.isNotBlank() && !key.contains("redact", true) } ?: return@mapNotNull null
            val parts = it.split('|', limit = 2); NzbIndexerRow(parts[0], parts[1], key)
        }
        return (koin.get<PandaConfigStateStore>().current.nzbIndexers + stored).filter { it.apiKey.isNotBlank() && !it.apiKey.contains("redact", true) }
            .map { it.copy(url = UsenetIndexerUrlResolver.resolve(it.type, it.url)) }.filter { it.url.isNotBlank() }.distinctBy { it.url }
    }
    private suspend fun sports(filter: String, days: Int): AppleBrowsePage {
        val connections = indexers()
        if (connections.isEmpty()) return AppleBrowsePage("Sports", emptyList(), "Sports browsing needs a direct Newznab indexer connection. Indexers configured inside an external extension are not available to this browser. You can still search that extension for sports releases.")
        val bucket = SportBucket.entries.firstOrNull { it.name == filter }
        val queries = when (bucket) {
            SportBucket.BASKETBALL -> listOf("Euroleague", "EuroCup", "NBA", "WNBA", "Basketball")
            SportBucket.SOCCER -> listOf("Bundesliga", "Champions League", "Premier League")
            SportBucket.F1 -> listOf("Formula 1", "F1")
            null -> emptyList()
            else -> bucket.keywords.take(3).map { it.replace('.', ' ').trim() }
        }
        val client = koin.get<NewznabClient>()
        val result = loadAppleSports(client, connections, queries, days)
        val now = Clock.System.now()
        val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
        val cutoff = if (days > 0) today.minus(days - 1, DateTimeUnit.DAY) else null
        val byId = linkedMapOf<String, NewznabItem>()
        val rows = result.items.filter { SportBucket.isSportsRelease(it.title, it.categoryId) && (bucket == null || SportBucket.classify(it.title) == bucket) }.mapNotNull { item ->
            val event = SportsReleaseDateParser.eventDate(item.title)?.let { LocalDate(it.year, it.monthNumber, it.dayOfMonth) }
            val uploaded = parseRfc1123Millis(item.pubDate)?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date }
            val date = event ?: uploaded
            if (cutoff != null && (date == null || date < cutoff || SportsReleaseDateParser.seasonEndYear(item.title)?.let { it < cutoff.year } == true)) return@mapNotNull null
            val id = "sports:${byId.size}"; byId[id] = item
            AppleBrowseRow(id, DisplayTextDecoder.decodeReleaseTitle(item.title),
                if (event != null) "Event · $event" else if (uploaded != null) "Uploaded · $uploaded" else "Event date unavailable",
                kind = "stream", size = item.sizeBytes ?: 0, time = date?.atStartOfDayIn(TimeZone.currentSystemDefault())?.toEpochMilliseconds() ?: 0)
        }
        sports = byId
        val warning = result.warning
        return AppleBrowsePage("Sports", rows,
            notice = if (warning != null && rows.isNotEmpty()) "Some indexers could not finish loading. Results from available indexers are shown.\n$warning"
                else if (warning == null) "Dates use the event date when present; otherwise the upload date. Results depend on your indexers." else null,
            error = warning?.takeIf { rows.isEmpty() })
    }
    private suspend fun cloud(folder: String, value: String): AppleBrowsePage {
        val service = DebridServiceType.entries.firstOrNull { it.name == value } ?: DebridServiceType.REAL_DEBRID
        if (service !in setOf(DebridServiceType.REAL_DEBRID, DebridServiceType.TORBOX)) return AppleBrowsePage("Cloud files", emptyList(), "This provider does not support Torve's cloud browser. Select Real-Debrid or TorBox.")
        val providerName = if (service == DebridServiceType.TORBOX) "TorBox" else "Real-Debrid"
        val key = cloudKey(service)?.takeIf { it.isNotBlank() } ?: return AppleBrowsePage("Cloud files", emptyList(), "Connect $providerName in Connections to browse your stored downloads.", "cloud")
        val client = koin.get<DebridClient>()
        val inventory = if (folder.isBlank()) client.cloudInventory(service, key) else null
        return AppleBrowsePage("Cloud files", if (inventory != null) inventory.folders.map {
            val kind = when { it.id.startsWith("usenet:") -> "Usenet"; it.id.startsWith("torrent:") -> "Torrent"; else -> null }
            AppleBrowseRow(it.id, it.name, listOfNotNull(kind, it.status.replace('_', ' ').takeIf(String::isNotBlank)).joinToString(" · "), kind = "folder", size = it.size)
        } else client.cloudFiles(service, key, folder).map {
            val video = it.name.substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "m4v", "mov", "ts", "webm", "avi")
            AppleBrowseRow(it.id, it.name, if (it.ready) "Ready" else "Preparing", kind = "stream", size = it.size, ready = it.ready && video)
        }, notice = inventory?.notice)
    }
    private suspend fun cloudKey(service: DebridServiceType) = secrets.get(when (service) {
        DebridServiceType.REAL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID
        DebridServiceType.TORBOX -> IntegrationSecretKey.DEBRID_API_KEY_TORBOX
        DebridServiceType.ALL_DEBRID -> IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID
        DebridServiceType.PREMIUMIZE -> IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE
    })
    @Throws(Exception::class)
    suspend fun play(id: String, onStatus: (String) -> Unit): String = lock.withLock {
        koin.get<UserIdProvider>().withPersonalDataScope {
            check(loadedOwner == owner() && visible.any { it.id == id && it.ready }) { "This item is no longer available. Refresh the page." }
            val scopeOwner = owner()
            val url = if (destination == "sports") {
                val item = sports[id] ?: error("Refresh the sports list.")
                val key = secrets.get(IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY, "torbox")?.takeIf { it.isNotBlank() }
                    ?: secrets.get(IntegrationSecretKey.DEBRID_API_KEY_TORBOX)?.takeIf { it.isNotBlank() }
                if (key != null) koin.get<TorBoxUsenetClient>().resolve(item.nzbUrl, key, onStatus).getOrThrow().streamUrl
                else {
                    val repo = koin.get<UsenetRepository>()
                    check(repo.getNzbdavStatus().configured) { "Connect TorBox Usenet or NzbDAV to play sports releases." }
                    BackendNzbResolver(repo).resolve(item.nzbUrl, onStatus).getOrThrow().streamUrl
                }
            } else {
                val service = DebridServiceType.valueOf(provider)
                koin.get<DebridClient>().playCloudFile(service, cloudKey(service) ?: error("Connect your cloud account."), parent, id)
            }
            check(scopeOwner == owner()) { "Your profile changed. Open the page again." }
            url
        }
    }
    @Throws(Exception::class)
    suspend fun requestAction(id: String, remove: Boolean) = lock.withLock {
        koin.get<UserIdProvider>().withPersonalDataScope {
            check(loadedOwner == owner()) { "Your profile changed. Refresh the page." }
            val row = visible.firstOrNull { it.id == id } ?: error("Refresh the request list.")
            val service = koin.get<MediaLifecycleService>()
            if (remove) { check(row.canDelete); check(service.deleteRequest(row.requestId)) { "Could not remove the request." } }
            else { check(row.canRetry); service.retry(row.requestId) }
            cachedPage = null
        }
    }
}
