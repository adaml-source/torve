package com.torve.presentation.home

import com.torve.domain.model.*
import com.torve.domain.repository.PreferencesRepository

internal const val HOME_STARTUP_SNAPSHOT_KEY = "home_startup_snapshot_v1"
internal const val HOME_COMPLETE_SNAPSHOT_KEY = "home_snapshot_v5"

/** Read the small presentation first; old installations retain their existing fallback. */
internal suspend fun readHomeStartupSnapshot(prefs: PreferencesRepository): String? =
    prefs.getString(HOME_STARTUP_SNAPSHOT_KEY) ?: prefs.getString(HOME_COMPLETE_SNAPSHOT_KEY)

/** A local resume row arriving first must not discard the rest of the saved Home. */
internal fun HomeUiState.withStartupFallback(cached: HomeUiState): HomeUiState =
    withInitialDiscoveryShelves(cached.shelves).copy(
        heroItem = heroItem ?: cached.heroItem,
        continueWatching = continueWatching.ifEmpty { cached.continueWatching },
        continueWatchingRatings = cached.continueWatchingRatings + continueWatchingRatings,
        watchlistShelf = watchlistShelf ?: cached.watchlistShelf,
        watchlistItems = watchlistItems.ifEmpty { cached.watchlistItems },
        upcomingSchedule = upcomingSchedule.ifEmpty { cached.upcomingSchedule },
        upcomingScheduleStatus = if (upcomingSchedule.isEmpty()) cached.upcomingScheduleStatus else upcomingScheduleStatus,
        becauseYouWatched = becauseYouWatched.ifEmpty { cached.becauseYouWatched },
        hiddenGemsShelf = hiddenGemsShelf ?: cached.hiddenGemsShelf,
        recentlyWatched = recentlyWatched.ifEmpty { cached.recentlyWatched },
        customShelves = (cached.customShelves + customShelves).toMutableMap(),
        addonShelves = addonShelves.ifEmpty { cached.addonShelves },
        addonShelfVisibility = cached.addonShelfVisibility + addonShelfVisibility,
        mdbListShelves = mdbListShelves.ifEmpty { cached.mdbListShelves },
        isLoading = false, error = null,
    )

/** Only presentation is bounded. Libraries, progress and source catalogue caches are untouched. */
internal fun HomeUiState.compactStartupSnapshot(): HomeUiState {
    fun card(item: MediaItem) = item.copy(
        overview = item.overview?.take(320), cast = emptyList(), seasons = emptyList(),
        studios = item.studios.take(2), genres = item.genres.take(6),
    )
    fun cards(items: List<MediaItem>) = items.take(8).map(::card)
    fun shelf(row: CatalogShelf?) = row?.copy(items = cards(row.items))
    fun shelves(rows: List<CatalogShelf>) = rows.take(4).map { it.copy(items = cards(it.items)) }
    val compact = copy(
        shelves = shelves(shelves), heroItem = heroItem?.let(::card),
        continueWatching = continueWatching.take(8), upcomingSchedule = cards(upcomingSchedule),
        watchlistShelf = shelf(watchlistShelf), watchlistItems = cards(watchlistItems),
        becauseYouWatched = shelves(becauseYouWatched), hiddenGemsShelf = shelf(hiddenGemsShelf),
        recentlyWatched = cards(recentlyWatched), customShelves = customShelves.entries.take(4)
            .associate { it.key to cards(it.value) },
        addonShelves = shelves(addonShelves), mdbListShelves = shelves(mdbListShelves),
        recommendedItems = emptyList(), popularActors = emptyList(), popularDirectors = emptyList(),
        searchResults = emptyList(), searchQuery = "", isSearching = false,
    )
    val items = compact.shelves.flatMap { it.items } + compact.upcomingSchedule + compact.watchlistItems +
        compact.becauseYouWatched.flatMap { it.items } + compact.recentlyWatched +
        compact.customShelves.values.flatten() + compact.addonShelves.flatMap { it.items } +
        compact.mdbListShelves.flatMap { it.items } + compact.hiddenGemsShelf?.items.orEmpty() +
        listOfNotNull(compact.heroItem) + compact.continueWatching.map { p ->
            MediaItem(id = p.mediaId, tmdbId = p.mediaId.extractTmdbIdOrNull(), type = p.mediaType,
                title = p.title, posterUrl = p.posterUrl, backdropUrl = p.backdropUrl)
        }
    val visibleRatingKeys = items.flatMap { it.ratingEnrichmentLookupKeys() }.toSet()
    return compact.copy(continueWatchingRatings = continueWatchingRatings.filterKeys { it in visibleRatingKeys })
}
