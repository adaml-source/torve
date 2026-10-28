package com.torve.presentation.catalog

import com.torve.data.auth.UserIdProvider
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class WatchedSeriesTotal(val count: Int, val fetchedAt: Long)

internal data class WatchedDiscoverySnapshot(
    val owner: String = "", val enabled: Boolean = false,
    val movies: Set<String> = emptySet(),
    val episodes: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    val totals: Map<String, WatchedSeriesTotal> = emptyMap(),
)

/** Discovery-only visibility. History, saved lists, resume and detail routes remain reachable. */
class WatchedDiscoveryFilter(
    private val prefs: PreferencesRepository,
    private val progress: WatchProgressRepository,
    private val history: WatchHistoryRepository,
    private val ids: UserIdProvider,
    private val metadata: MetadataRepository,
    notifier: SettingsRefreshNotifier,
    private val currentOwner: () -> String = ids::currentDataUserId,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val lookupAfter = mutableMapOf<String, Long>()
    internal val snapshot = MutableStateFlow(WatchedDiscoverySnapshot())
    val changes: Flow<Unit> = snapshot.map { Unit }

    init {
        scope.launch { notifier.events.debounce(150).collect { refresh() } }
        scope.launch { progress.progressChanges.debounce(150).collect { refresh() } }
    }

    fun close() { scope.cancel() }

    suspend fun refresh() = lock.withLock {
        val owner = currentOwner()
        val enabled = prefs.getString(SettingsViewModel.KEY_HIDE_WATCHED) == "true"
        if (!enabled) {
            snapshot.value = WatchedDiscoverySnapshot(owner = owner, totals = snapshot.value.totals.takeIf { snapshot.value.owner == owner }.orEmpty())
            return@withLock
        }
        val saved = progress.getAllProgress()
        val watched = history.getAll()
        if (owner != currentOwner()) return@withLock
        val completedMovies = saved.filter { it.mediaType == MediaType.MOVIE && it.isWatched() }.map { it.mediaId }
        val importedMovies = watched.filter {
            MediaType.fromString(it.mediaType) == MediaType.MOVIE &&
                (it.id.startsWith("trakt_") || it.id.startsWith("simkl_") || it.id == "${it.mediaId}_completed")
        }.map { it.mediaId }
        val movies = (completedMovies + importedMovies).flatMap { keys(it) }.toSet()
        val episodes = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        fun add(id: String, season: Int?, episode: Int?) {
            if (season == null || episode == null || season <= 0 || episode <= 0) return
            keys(id).forEach { episodes.getOrPut(it) { mutableSetOf() }.add(season to episode) }
        }
        saved.filter { it.mediaType == MediaType.SERIES && it.isWatched() }.forEach { add(it.mediaId, it.seasonNumber, it.episodeNumber) }
        watched.filter { MediaType.fromString(it.mediaType) == MediaType.SERIES && it.countsAsWatchedEpisode() }
            .forEach { add(it.mediaId, it.seasonNumber, it.episodeNumber) }
        val old = snapshot.value
        snapshot.value = WatchedDiscoverySnapshot(owner, true, movies, episodes,
            if (old.owner == owner) old.totals else emptyMap())
    }

    fun allows(item: MediaItem, context: ContentAccessContext): Boolean {
        if (context !in DISCOVERY_CONTEXTS) return true
        val current = snapshot.value
        if (!current.enabled || current.owner != currentOwner()) return true
        val identity = (keys(item.id) + listOfNotNull(item.tmdbId?.let { "tmdb:$it" }, item.imdbId?.lowercase())).toSet()
        if (item.type == MediaType.MOVIE) return identity.none { it in current.movies }
        val watched = identity.flatMap { current.episodes[it].orEmpty() }.toSet()
        if (watched.isEmpty()) return true
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val total = item.seasons.filter { it.seasonNumber > 0 }.sumOf { it.episodeCount }
            .takeIf { it > 0 } ?: identity.firstNotNullOfOrNull { current.totals[it] }
                ?.takeIf { now - it.fetchedAt < 15 * 60_000L }?.count
        if (total != null) return watched.size < total
        // Fetch counts only for encountered watched shows, never the whole library.
        item.tmdbId?.let { lookupTotal(item, it, current.owner, identity) }
            ?: item.id.extractTmdbIdOrNull()?.let { lookupTotal(item, it, current.owner, identity) }
        return true // Partial/unknown series are never assumed complete.
    }

    private fun lookupTotal(item: MediaItem, id: Int, owner: String, identity: Set<String>) {
        scope.launch {
            val request = "$owner:tv:$id"
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            if (!lock.withLock {
                if (now < (lookupAfter[request] ?: 0)) false
                else { lookupAfter[request] = now + 30_000L; true }
            }) return@launch
            try {
                val detail = withTimeout(5000) { metadata.getDetail("tv", id) }
                val total = detail.seasons.filter { it.seasonNumber > 0 }.sumOf { it.episodeCount }
                if (total > 0 && currentOwner() == owner) snapshot.update { old ->
                    if (old.owner == owner) old.copy(totals = old.totals + identity.associateWith {
                        WatchedSeriesTotal(total, kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                    }) else old
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Metadata failure leaves the show visible. */ }
        }
    }

    companion object {
        internal fun keys(id: String): Set<String> = buildSet {
            add(id.lowercase())
            if (!id.isCatalogPlaybackIdentity()) return@buildSet
            id.extractTmdbIdOrNull()?.let { add("tmdb:$it") }
            id.extractImdbIdOrNull()?.let { add(it.lowercase()) }
        }
        private val DISCOVERY_CONTEXTS = setOf(ContentAccessContext.DEFAULT_DISCOVERY,
            ContentAccessContext.GLOBAL_RECOMMENDATION, ContentAccessContext.SEARCH_SUGGESTION,
            ContentAccessContext.DIRECT_SEARCH, ContentAccessContext.ADDON_SHELF,
            ContentAccessContext.SIMILAR_OR_MORE_LIKE_THIS)
    }
}
