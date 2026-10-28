package com.torve.presentation.home

import com.torve.domain.model.*
import com.torve.presentation.contentpolicy.ContentPolicyFilter

/** Cached presentation is untrusted after a role or source-rule change. */
internal fun HomeUiState.applyFamilyBoundary(filter: ContentPolicyFilter): HomeUiState {
    if (!filter.familyEnabled) return this
    fun visible(items: List<MediaItem>) = items.filter(filter::familyAllows)
    fun shelves(rows: List<CatalogShelf>) = rows.map { it.copy(items = visible(it.items)) }.filter { it.items.isNotEmpty() }
    fun shelf(row: CatalogShelf?) = row?.copy(items = visible(row.items))?.takeIf { it.items.isNotEmpty() }
    return copy(shelves = shelves(shelves), heroItem = heroItem?.takeIf(filter::familyAllows),
        continueWatching = continueWatching.filter { filter.familyAllows(MediaItem(it.mediaId, type = it.mediaType, title = it.title)) },
        continueWatchingRatings = emptyMap(), recommendedItems = recommendedItems.filter { filter.familyAllows(it.item) },
        upcomingSchedule = visible(upcomingSchedule), watchlistShelf = shelf(watchlistShelf), watchlistItems = visible(watchlistItems),
        becauseYouWatched = shelves(becauseYouWatched), hiddenGemsShelf = shelf(hiddenGemsShelf), recentlyWatched = visible(recentlyWatched),
        customShelves = customShelves.mapValues { visible(it.value) }.filterValues { it.isNotEmpty() }, addonShelves = shelves(addonShelves),
        mdbListShelves = shelves(mdbListShelves), searchResults = visible(searchResults),
        popularActors = if (filter.familyKidsProfile) emptyList() else popularActors,
        popularDirectors = if (filter.familyKidsProfile) emptyList() else popularDirectors)
}
