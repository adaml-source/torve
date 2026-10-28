package com.torve.presentation.watching

import com.torve.data.auth.UserIdProvider
import com.torve.data.profile.HouseholdSyncService
import com.torve.data.simkl.SimklSyncService
import com.torve.data.mdblist.RatingsEnricher
import com.torve.data.mdblist.MdbListApi
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.settings.SettingsRefreshNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.*
import kotlinx.serialization.Serializable

@Serializable
data class WatchingEpisode(val show: MediaItem, val season: Int, val episode: Episode,
    val date: LocalDate?, val remainingAired: Int = 0, val resume: Boolean = false, val watched: Boolean = false,
    val positionMs: Long? = null, val progressPercent: Float? = null)

enum class WatchingHubError { PARTIAL_REFRESH, REFRESH_FAILED, MARK_FAILED }
data class WatchingHubState(val loading: Boolean = false, val upNext: List<WatchingEpisode> = emptyList(),
    val calendar: List<WatchingEpisode> = emptyList(), val error: String? = null, val incompleteShows: Int = 0,
    val today: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
    val markingEpisodes: Set<String> = emptySet(), val hideSpoilers: Boolean = false,
    val episodeNotificationsEnabled: Boolean = false, val personalDataUserId: String? = null,
    val errorKind: WatchingHubError? = null, val showRemainingEpisodes: Boolean = true)

class WatchingHubViewModel(private val metadata: MetadataRepository, private val history: WatchHistoryRepository,
    private val progress: WatchProgressRepository, private val watchlist: WatchlistRepository,
    private val userIds: UserIdProvider, private val household: HouseholdSyncService,
    private val simkl: SimklSyncService, private val notifier: SettingsRefreshNotifier,
    private val profiles: ProfileRepository? = null,
    private val contentPolicy: com.torve.data.contentpolicy.ContentPolicyRepository? = null,
    private val prefs: PreferencesRepository? = null,
    private val ratingsEnricher: RatingsEnricher? = null,
    private val integrationSecretStore: IntegrationSecretStore? = null,
    private val family: com.torve.data.profile.FamilyControls? = null,
    private val showMetadata: (suspend (MediaItem) -> MediaItem)? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var librarySyncJob: Job? = null
    private var librarySyncPerson: String? = null
    private var displayedPerson: String? = null
    private var snapshotJob: Job? = null
    private val snapshots = prefs?.let { WatchingHubSnapshotStore(it, userIds::currentDataUserIdOrNull) }
    private val schedules = mutableMapOf<String, Pair<Long, List<WatchingEpisode>>>()
    private val _state: MutableStateFlow<WatchingHubState> = family?.let { com.torve.data.profile.ProfilePolicyStateFlow(it, WatchingHubState()) } ?: MutableStateFlow(WatchingHubState())
    val state = _state.asStateFlow()
    init {
        refresh()
        // Authentication/profile restoration can finish after the initial local read.
        // A refresh cancelled by that identity change must be replaced for the new person.
        scope.launch {
            household.state.map { it.personalDataUserId }.distinctUntilChanged().collect { person ->
                if (person == userIds.currentDataUserIdOrNull() && displayedPerson != userIds.currentDataUserId()) refresh()
            }
        }
        scope.launch { notifier.events.debounce(700L).collect { refresh() } }
        scope.launch { progress.progressChanges.debounce(700L).collect { refresh() } }
    }
    fun clear() { scope.cancel() }
    fun refresh(force: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch(com.torve.data.auth.PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
            val person = userIds.currentDataUserId()
            if (displayedPerson != person) {
                snapshotJob?.cancel()
                _state.value = WatchingHubState(personalDataUserId = person); schedules.clear(); displayedPerson = person
            }
            if (force) schedules.clear()
            _state.update { it.copy(loading = true, error = null, errorKind = null) }
            try {
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                // Restore the presentation before unrelated settings reads or library work.
                // Profile/SQLite reads must not stop SwiftUI from drawing cached images.
                val saved = if (_state.value.upNext.isEmpty() && _state.value.calendar.isEmpty()) snapshots?.read() else null
                val maxRating = withContext(com.torve.util.ioDispatcher) { profiles?.getActiveProfile()?.maxContentRating }
                val policyFilter = com.torve.presentation.contentpolicy.ContentPolicyFilter(family)
                fun allowed(value: WatchingEpisode): Boolean =
                    com.torve.presentation.contentpolicy.ContentPolicyFilter(family).filterProfileLimit(listOf(value.show), maxRating).isNotEmpty() &&
                        (contentPolicy == null || policyFilter.filterItems(policy = contentPolicy.state.value,
                            context = ContentAccessContext.HISTORY_DERIVED, items = listOf(value.show),
                            sourceType = ContentSourceType.TMDB).items.any { !it.isContentPlaceholder && !it.isStubDetail })
                // Restore before any sync, TMDB detail or season requests. Current restrictions
                // are applied again, so saved cards cannot bypass a changed profile or lock.
                if (_state.value.upNext.isEmpty() && _state.value.calendar.isEmpty()) {
                    if (saved != null && person == userIds.currentDataUserId()) _state.update { it.copy(
                        upNext = saved.upNext.filter { value -> allowed(value) },
                        calendar = saved.calendar.filter { value -> allowed(value) && value.date != null &&
                            value.date >= today.minus(7, DateTimeUnit.DAY) && value.date <= today.plus(30, DateTimeUnit.DAY) },
                        hideSpoilers = true,
                    ) }
                }
                val showRemaining = prefs?.getString(com.torve.presentation.settings.SettingsViewModel.KEY_SHOW_REMAINING_EPISODES) != "false"
                val hideSpoilers = prefs?.getString(KEY_HIDE_UNWATCHED_SPOILERS) == "true"
                val notifications = prefs?.getString(com.torve.presentation.calendar.CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED) == "true"
                _state.update { it.copy(today = today, hideSpoilers = hideSpoilers, showRemainingEpisodes = showRemaining,
                    episodeNotificationsEnabled = notifications, personalDataUserId = person) }
                // Saved rows are immediately useful while a large remote library syncs.
                // Sync completion already notifies this VM to refresh its local snapshot.
                if (librarySyncPerson != person) {
                    librarySyncJob?.cancel(); librarySyncJob = null; librarySyncPerson = person
                }
                if (librarySyncJob?.isActive != true) {
                    librarySyncJob = scope.launch(com.torve.data.auth.PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
                        try { household.refresh(force); simkl.sync(force) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Connection status owns sync errors; keep local schedules available. */ }
                    }
                }
                if (person != userIds.currentDataUserId()) return@launch
                val allHistory = withContext(com.torve.util.ioDispatcher) { history.getAll() }
                val allProgress = withContext(com.torve.util.ioDispatcher) { progress.getAllProgress() }
                val savedShows = withContext(com.torve.util.ioDispatcher) { watchlist.getByType("series") }
                val recent = watchingShowSeeds(allProgress, allHistory, savedShows)
                val lastActivity = recent.flatMap { seed -> watchingShowAliases(seed.show).map { it to seed.activityAt } }.toMap()
                val recentKeys = lastActivity.keys
                // A removed show must not be resurrected by the presentation cache.
                _state.update { it.copy(upNext = it.upNext.filter { value -> watchingShowAliases(value.show).any { key -> key in recentKeys } && allowed(value) },
                    calendar = it.calendar.filter { value -> watchingShowAliases(value.show).any { key -> key in recentKeys } && allowed(value) }) }
                val limiter = Semaphore(3)
                var failures = 0
                val failedShows = mutableSetOf<String>()
                _state.update { it.copy(today = today, hideSpoilers = hideSpoilers,
                    episodeNotificationsEnabled = notifications, personalDataUserId = person) }
                val results = coroutineScope {
                    val completed = mutableListOf<Pair<WatchingEpisode?, List<WatchingEpisode>>>()
                    val ratingUpdates = Channel<Unit>(Channel.CONFLATED)
                    val ratingsJob = launch {
                        for (signal in ratingUpdates) { delay(250); enrichShowRatings(person) }
                    }
                    try { recent.map { seed -> async {
                    val resolved = limiter.withPermit {
                        val key = watchingShowKey(seed.show)
                        val local = allProgress.firstOrNull { it.mediaId in seed.mediaIds && it.mediaType == MediaType.SERIES }
                        val watched = allHistory.filter { it.mediaId in seed.mediaIds && it.countsAsWatchedEpisode() }
                            .mapNotNull { h -> h.seasonNumber?.let { s -> h.episodeNumber?.let { e -> s to e } } }.toMutableSet()
                        var retainedShow = seed.show
                        try {
                            val detail = showMetadata?.invoke(seed.show) ?: run {
                                val id = seed.show.tmdbId ?: seed.show.imdbId?.let { metadata.findByImdbId(it, "tv")?.tmdbId }
                                    ?: error("Show metadata unavailable")
                                metadata.getDetail("tv", id)
                            }
                            val savedHistory = allHistory.filter { it.mediaId in seed.mediaIds }.maxByOrNull { it.watchedAt }
                            val show = retainWatchingArtwork(detail, local, savedHistory, seed.show.posterUrl)
                            retainedShow = show
                            if (com.torve.presentation.contentpolicy.ContentPolicyFilter(family).filterProfileLimit(listOf(show), maxRating).isEmpty() ||
                                (contentPolicy != null && policyFilter.filterItems(policy = contentPolicy.state.value,
                                    context = ContentAccessContext.HISTORY_DERIVED, items = listOf(show), sourceType = ContentSourceType.TMDB).items.none { !it.isContentPlaceholder && !it.isStubDetail }))
                                return@withPermit null to emptyList<WatchingEpisode>()
                            val now = Clock.System.now().toEpochMilliseconds()
                            val cached = schedules[key]?.takeIf { now - it.first < 20 * 60_000L }?.second
                            val schedule = cached?.let { WatchingSchedule(it, emptySet()) }
                                ?: watchingSchedule(show, metadata::getSeasonDetail)
                            if (show.seasons.isEmpty()) error("Episode schedule unavailable")
                            if (schedule.failedSeasons.isNotEmpty()) { failures++; failedShows += key }
                            else if (person == userIds.currentDataUserId()) schedules[key] = now to schedule.episodes
                            val episodes = schedule.episodes.map { it.copy(show = show) }
                            if (local != null && local.isWatched() && local.seasonNumber != null && local.episodeNumber != null)
                                watched += local.seasonNumber to local.episodeNumber
                            val next = chooseUpNext(episodes, watched, local, today)?.takeIf { next ->
                                next.resume || schedule.failedSeasons.none { it <= next.season }
                            } ?: savedWatchingResume(show, local, watched)
                            val calendar = episodes.filter { it.date != null && it.date >= today.minus(7, DateTimeUnit.DAY) &&
                                it.date <= today.plus(30, DateTimeUnit.DAY) }.map { it.copy(watched = (it.season to it.episode.episodeNumber) in watched) }
                            next to calendar
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            failures++
                            failedShows += key
                            val fallback = savedWatchingResume(retainedShow, local, watched)?.takeIf { allowed(it) }
                            fallback to emptyList<WatchingEpisode>()
                        }
                    }
                    if (person == userIds.currentDataUserId()) {
                        completed += resolved
                        _state.update { it.withCompletedSchedules(completed, lastActivity,
                            loading = completed.size < recent.size, failures = failures, failedShowKeys = failedShows) }
                        saveSnapshot(person)
                        if (resolved.first != null || resolved.second.isNotEmpty()) ratingUpdates.trySend(Unit)
                    }
                    resolved
                    } }.awaitAll() }
                    finally { ratingUpdates.close(); ratingsJob.join() }
                }
                if (person != userIds.currentDataUserId()) return@launch
                _state.update { it.withCompletedSchedules(results, lastActivity, loading = false, failures = failures,
                    failedShowKeys = failedShows) }
                saveSnapshot(person)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (person == userIds.currentDataUserId()) _state.update { it.copy(loading = false, error = "Could not refresh your shows. Your saved library is still available.", errorKind = WatchingHubError.REFRESH_FAILED) }
            }
        }
    }
    private fun saveSnapshot(person: String) {
        snapshotJob?.cancel()
        snapshotJob = scope.launch(com.torve.data.auth.PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
            delay(250)
            if (person == userIds.currentDataUserId()) snapshots?.write(_state.value)
        }
    }
    private suspend fun enrichShowRatings(person: String?) {
        val enricher = ratingsEnricher ?: return
        val shows = (_state.value.upNext + _state.value.calendar).map { it.show }
            .distinctBy { "${it.type}:${it.tmdbId ?: it.id}" }
        if (shows.isEmpty()) return
        try {
            val apiKey = integrationSecretStore?.get(IntegrationSecretKey.MDBLIST_API_KEY)
                ?: prefs?.getString(com.torve.presentation.settings.SettingsViewModel.KEY_MDBLIST_API_KEY)
                ?: MdbListApi.DEFAULT_API_KEY
            val enriched = withContext(com.torve.util.ioDispatcher) { enricher.enrichList(shows, apiKey, requirePrimaryProviders = true) }
            if (person != userIds.currentDataUserId()) return
            _state.update { if (it.personalDataUserId == person) it.withEnrichedShowRatings(enriched) else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep playable rows and their existing ratings when a provider is unavailable. */ }
    }
    fun markWatched(value: WatchingEpisode) {
        if (value.watched || value.date == null || value.date > _state.value.today) return
        val key = "${value.show.id}_${value.season}_${value.episode.episodeNumber}"
        if (key in _state.value.markingEpisodes) return
        val person = userIds.currentDataUserId()
        _state.update { it.copy(markingEpisodes = it.markingEpisodes + key) }
        scope.launch(com.torve.data.auth.PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
            try {
            val now = Clock.System.now().toEpochMilliseconds()
            history.record(WatchHistoryEntry(id = "${value.show.id}_s${value.season}e${value.episode.episodeNumber}",
                mediaId = value.show.id, mediaType = "series", title = value.episode.name,
                posterUrl = value.show.posterUrl, backdropUrl = value.show.backdropUrl, watchedAt = now,
                durationWatchedMs = 0, seasonNumber = value.season, episodeNumber = value.episode.episodeNumber,
                showTitle = value.show.title))
            if (person == userIds.currentDataUserId()) refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (person == userIds.currentDataUserId()) _state.update { it.copy(error = "Could not mark this episode watched. Try again.", errorKind = WatchingHubError.MARK_FAILED) }
            } finally {
                if (person == userIds.currentDataUserId()) _state.update { it.copy(markingEpisodes = it.markingEpisodes - key) }
            }
        }
    }
    suspend fun setEpisodeNotificationsEnabled(enabled: Boolean) {
        withContext(com.torve.data.auth.PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
            val person = userIds.currentDataUserId()
            prefs?.setString(com.torve.presentation.calendar.CalendarViewModel.KEY_EPISODE_NOTIFICATIONS_ENABLED, enabled.toString())
            if (person == userIds.currentDataUserId()) _state.update { it.copy(episodeNotificationsEnabled = enabled) }
        }
    }
    fun setHideSpoilers(enabled: Boolean) {
        scope.launch(com.torve.data.auth.PersonalDataScope(userIds.currentUserId(), userIds.currentProfileId())) {
            val person = userIds.currentDataUserId()
            prefs?.setString(KEY_HIDE_UNWATCHED_SPOILERS, enabled.toString())
            if (person == userIds.currentDataUserId()) {
                _state.update { it.copy(hideSpoilers = enabled) }
                notifier.notifyRefresh(Clock.System.now().toEpochMilliseconds())
            }
        }
    }
}

/** A slow schedule must not hold every ready show behind a blank loading screen. */
internal fun WatchingHubState.withCompletedSchedules(
    completed: List<Pair<WatchingEpisode?, List<WatchingEpisode>>>, activity: Map<String, Long>,
    loading: Boolean, failures: Int, failedShowIds: Set<Int> = emptySet(), failedShowKeys: Set<String> = emptySet(),
): WatchingHubState {
    val next = completed.mapNotNull { it.first }
    val upcoming = completed.flatMap { it.second }
    val readyShows = (next + upcoming).flatMap { watchingShowAliases(it.show) }.toSet()
    // Keep the previous library during a refresh; discard stale rows only at completion.
    val retainedNext = upNext.filter { (loading || it.show.tmdbId in failedShowIds || watchingShowAliases(it.show).any(failedShowKeys::contains)) && watchingShowAliases(it.show).none(readyShows::contains) }
    val retainedCalendar = calendar.filter { (loading || it.show.tmdbId in failedShowIds || watchingShowAliases(it.show).any(failedShowKeys::contains)) && watchingShowAliases(it.show).none(readyShows::contains) }
    val result = copy(
        loading = loading,
        upNext = (next + retainedNext).sortedWith(compareByDescending<WatchingEpisode> { it.resume }
            .thenByDescending { watchingShowAliases(it.show).maxOfOrNull { key -> activity[key] ?: 0L } ?: 0L })
            .distinctBy { watchingShowKey(it.show) },
        calendar = (upcoming + retainedCalendar).distinctBy { Triple(it.show.tmdbId ?: it.show.id, it.season, it.episode.episodeNumber) }
            .sortedWith(compareBy<WatchingEpisode> { it.date }.thenBy { it.show.title }),
        incompleteShows = failures,
        error = if (failures > 0) "Some episode schedules could not be refreshed. Try Refresh." else null,
        errorKind = WatchingHubError.PARTIAL_REFRESH.takeIf { failures > 0 },
    )
    return result.withEnrichedShowRatings((upNext + calendar).map { it.show }.filter { it.ratings != null })
}

internal fun WatchingHubState.withEnrichedShowRatings(enriched: List<MediaItem>): WatchingHubState {
    val ratings = enriched.associateBy { "${it.type}:${it.tmdbId ?: it.id}" }
    fun WatchingEpisode.enriched(): WatchingEpisode {
        val updated = ratings["${show.type}:${show.tmdbId ?: show.id}"]?.ratings ?: return this
        return copy(show = show.copy(ratings = updated))
    }
    return copy(upNext = upNext.map { it.enriched() }, calendar = calendar.map { it.enriched() })
}

/** Restored history still supplies artwork when a metadata response omits one aspect. */
internal fun retainWatchingArtwork(detail: MediaItem, progress: WatchProgress?, history: WatchHistoryEntry?,
    watchlistPoster: String?): MediaItem = detail.copy(
    backdropUrl = listOf(detail.backdropUrl, progress?.backdropUrl, history?.backdropUrl).firstOrNull { !it.isNullOrBlank() },
    posterUrl = listOf(detail.posterUrl, progress?.posterUrl, history?.posterUrl, watchlistPoster).firstOrNull { !it.isNullOrBlank() },
)

internal fun chooseUpNext(episodes: List<WatchingEpisode>, watched: Set<Pair<Int, Int>>, progress: WatchProgress?,
    today: LocalDate): WatchingEpisode? {
    val aired = episodes.filter { it.date != null && it.date <= today }
        .filterNot { (it.season to it.episode.episodeNumber) in watched }
        .sortedWith(compareBy<WatchingEpisode> { it.season }.thenBy { it.episode.episodeNumber })
    val partial = if (progress != null && progress.positionMs > 0 && !progress.isWatched()) aired.firstOrNull {
        it.season == progress.seasonNumber && it.episode.episodeNumber == progress.episodeNumber
    } else null
    return (partial ?: aired.firstOrNull())?.copy(remainingAired = aired.size, resume = partial != null,
        positionMs = progress?.positionMs?.takeIf { partial != null },
        progressPercent = progress?.progressPercent?.takeIf { partial != null })
}
