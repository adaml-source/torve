package com.torve.presentation.home

import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock

data class NowStreamingState(
    val items: List<MediaItem> = emptyList(),
    val serviceIds: List<Int> = emptyList(),
    val selectedProviderId: Int? = null,
    val region: String = "US",
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

internal data class NowStreamingConfiguration(
    val owner: String, val region: String, val language: String,
    val services: Set<Int>, val enabled: Boolean,
    val maximumRating: ContentRating?, val policy: ContentPolicyState,
)

/** A retained, bounded provider catalogue. It never falls back to unscoped discovery. */
class NowStreamingCatalogue internal constructor(
    private val repository: MetadataRepository,
    private val configuration: suspend () -> NowStreamingConfiguration,
    private val owner: () -> String,
    private val policyFilter: ContentPolicyFilter,
    changes: Flow<Unit>,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val hydrateRatings: (List<MediaItem>) -> List<MediaItem> = { it },
    private val enrichRatings: suspend (List<MediaItem>) -> List<MediaItem> = { it },
    private val ratingsDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = policyFilter.profileState(NowStreamingState())
    val state: StateFlow<NowStreamingState> = _state.asStateFlow()
    private data class Page(val items: List<MediaItem>, val page: Int, val lastPages: Map<String, Int>, val savedAt: Long) {
        val lastPage: Int get() = lastPages.values.maxOrNull() ?: 1
    }
    private val pages = linkedMapOf<Int?, Page>()
    private var config: NowStreamingConfiguration? = null
    private var selected: Int? = null
    private var job: Job? = null
    private var ratingsJob: Job? = null
    private val ratingAttempts = linkedMapOf<String, Long>()
    private var generation = 0

    init {
        scope.launch { changes.onStart { emit(Unit) }.collect { request(reconfigure = true, force = true) } }
    }

    fun selectProvider(providerId: Int?) {
        val current = config ?: return
        if (providerId != null && providerId !in current.services) return
        if (providerId == selected) return
        selected = providerId
        request()
    }

    fun refresh() = request(reconfigure = true, force = true)
    fun loadMore() {
        if (_state.value.loading || !_state.value.hasMore) return
        request(more = true)
    }

    private fun request(reconfigure: Boolean = false, force: Boolean = false, more: Boolean = false) {
        job?.cancel()
        ratingsJob?.cancel()
        val ticket = ++generation
        job = scope.launch {
            try {
                val current = if (reconfigure || config == null) configuration() else config!!
                ensureActive()
                if (ticket != generation || owner() != current.owner) return@launch
                if (current != config) {
                    pages.clear()
                    ratingAttempts.clear()
                    if (current.owner != config?.owner || selected !in current.services) selected = null
                    config = current
                }
                val ids = current.services.filter { it in streamingServiceNames }.sorted()
                val chosen = selected
                fun active() = ticket == generation && owner() == current.owner && config == current && selected == chosen
                val cached = pages[chosen]
                val visible = cached?.items?.let { filter(current, it) }.orEmpty()
                _state.value = NowStreamingState(visible, ids, chosen, current.region, current.enabled,
                    hasMore = cached?.let { it.page < it.lastPage && it.items.size < 400 } == true)
                if (!current.enabled || ids.isEmpty()) return@launch
                if (force) ratingAttempts.clear()
                if (!more && !force && cached != null && now() - cached.savedAt in 0..300_000) {
                    launchRatings(chosen, ::active)
                    return@launch
                }
                _state.update { it.copy(loading = true) }
                val nextPage = if (more && cached != null) cached.page + 1 else 1
                val services = chosen?.let { listOf(it) } ?: ids
                val result = coroutineScope {
                    listOf("movie", "tv").filter { !more || nextPage <= (cached?.lastPages?.get(it) ?: 1) }.map { type -> async {
                        val names = repository.getWatchProviderNames(type, current.region)
                        ensureActive()
                        val resolved = services.flatMap { id -> resolveStreamingProviderIds(
                            streamingServiceNames.getValue(id), id, current.region,
                            names.map { StreamingProviderCandidate(it.key, it.value) },
                        ) }.distinct().sorted()
                        check(resolved.isNotEmpty())
                        type to repository.discover(type, page = nextPage, sortBy = "popularity.desc",
                            withWatchProviders = resolved.joinToString("|"), watchRegion = current.region)
                    } }.awaitAll()
                }
                ensureActive()
                if (!active()) return@launch
                val incoming = filter(current, result.flatMap { it.second.items })
                // Every provider-scoped page ranks movies and shows by current TMDB popularity.
                val items = ((if (more) cached?.items.orEmpty() else emptyList()) + incoming)
                    .distinctBy { it.stableKey() }.sortedByDescending { it.popularity ?: 0.0 }.take(400)
                val saved = Page(items, nextPage, cached?.lastPages.orEmpty() + result.associate { it.first to it.second.totalPages }, now())
                pages[chosen] = saved
                while (pages.size > 8) pages.remove(pages.keys.first())
                _state.update { it.copy(items = items, loading = false,
                    hasMore = nextPage < saved.lastPage && items.size < 400, error = null) }
                launchRatings(chosen, ::active)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == generation && config?.owner == owner()) _state.update {
                    it.copy(loading = false, error = "Streaming availability could not be refreshed. Try again.")
                }
            }
        }
    }

    /** Ratings may improve published posters, never delay discovery or replace another service/profile. */
    private fun launchRatings(chosen: Int?, active: () -> Boolean) {
        ratingsJob?.cancel()
        ratingsJob = scope.launch {
            fun apply(before: List<MediaItem>, after: List<MediaItem>) {
                if (!active()) return
                val page = pages[chosen] ?: return
                val items = HomeRatingsUpdate(before, after).apply(page.items)
                pages[chosen] = page.copy(items = items)
                _state.update { it.copy(items = items) }
            }
            try {
                val original = pages[chosen]?.items.orEmpty()
                val restored = try { withContext(ratingsDispatcher) { hydrateRatings(original) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { original }
                ensureActive()
                if (!active()) return@launch
                apply(original, restored)
                // Home displays 20 posters. A bounded pass also covers one newly loaded page;
                // retained pagination must not launch hundreds of lookups at once.
                val pending = restored.filter { item ->
                    item.hasExternalRatingLookupIdentity() &&
                        (item.ratings?.imdbScore == null || item.ratings?.rottenTomatoesScore == null) &&
                        ratingAttempts["$chosen:${item.stableKey()}"]?.let { now() - it in 0..300_000 } != true
                }.take(40)
                for (batch in pending.chunked(4)) {
                    ensureActive()
                    val enriched = try {
                        withTimeoutOrNull(8_000) { withContext(ratingsDispatcher) { enrichRatings(batch) } }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    ensureActive()
                    if (!active()) return@launch
                    if (enriched != null) {
                        apply(batch, enriched)
                        batch.forEach { ratingAttempts["$chosen:${it.stableKey()}"] = now() }
                        while (ratingAttempts.size > 1024) ratingAttempts.remove(ratingAttempts.keys.first())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep valid availability posters and their existing ratings. */ }
        }
    }

    private fun filter(config: NowStreamingConfiguration, items: List<MediaItem>): List<MediaItem> =
        policyFilter.filterItems(config.policy, ContentAccessContext.DEFAULT_DISCOVERY,
            policyFilter.filterProfileLimit(items, config.maximumRating), ContentSourceType.TMDB,
            strictUnknown = true).items.filter { !it.isContentPlaceholder && !it.isStubDetail }

    fun close() { scope.cancel() }
}

/** Shared display names; preferences store the stable service IDs, never a second favourites list. */
val streamingServiceNames = linkedMapOf(
    8 to "Netflix", 9 to "Prime Video", 337 to "Disney+", 350 to "Apple TV+", 1899 to "Max",
    15 to "Hulu", 531 to "Paramount+", 386 to "Peacock", 283 to "Crunchyroll", 11 to "Mubi",
    43 to "Starz", 37 to "Showtime", 380 to "BritBox", 258 to "Criterion", 73 to "Tubi",
    300 to "Pluto TV", 613 to "Freevee", 190 to "Curiosity Stream", 439 to "Shudder",
    30 to "WOW", 298 to "RTL+", 421 to "Joyn", 551 to "MagentaTV",
)
