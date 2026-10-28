package com.torve.presentation.apple

import com.torve.domain.model.*
import com.torve.presentation.home.AppleHomeLayoutRow

/** Preserve the complete configured layout, including enabled rows without data. */
object AppleTvHomeLayout {
    /** Reuse the enabled, policy-filtered extension inventory without requiring TMDB. */
    fun catalogues(shelves: List<CatalogShelf>, type: MediaType): List<CatalogShelf> = shelves.mapNotNull { shelf ->
        shelf.items.filter { it.type == type }.dedupeByStableKey().takeIf { it.isNotEmpty() }?.let { items ->
            shelf.copy(title = com.torve.presentation.home.AppleHomeLayoutPolicy.catalogueTitle(shelf.copy(items = items)), items = items)
        }
    }
    private val catalogueSections = setOf(HomeSection.HERO, HomeSection.ACTORS, HomeSection.DIRECTORS,
        HomeSection.STREAMING_SERVICES, HomeSection.TRENDING_MOVIES, HomeSection.TRENDING_TV,
        HomeSection.POPULAR_MOVIES, HomeSection.NOW_PLAYING, HomeSection.RECOMMENDED, HomeSection.TOP_RATED,
        HomeSection.HIDDEN_GEMS, HomeSection.BECAUSE_YOU_WATCHED, HomeSection.BECAUSE_YOU_WATCHED_MOVIES,
        HomeSection.BECAUSE_YOU_WATCHED_TV, HomeSection.NEW_RELEASES)

    fun shelves(rows: List<AppleHomeLayoutRow>, specialItems: Map<HomeSection, List<AppleTvRow>>,
                catalogueEnabled: Boolean = true, mediaRow: (MediaItem) -> AppleTvRow): List<AppleTvShelf> = rows
        .filter { catalogueEnabled || it.section !in catalogueSections }.map { row ->
        val items = specialItems[row.section] ?: if (row.people.isNotEmpty()) row.people.map { person ->
            AppleTvRow("person:${person.id}", person.name, artwork = person.profileUrl,
                mediaType = "person", targetId = person.id.toString())
        } else row.items.map(mediaRow)
        val message = when (row.section) {
            HomeSection.ON_NOW -> "Your favorite and recently watched channels will appear here."
            HomeSection.UP_NEXT -> "Your next episodes will appear here as you watch shows."
            HomeSection.CONTINUE_WATCHING -> "Movies and episodes you start will appear here."
            HomeSection.WATCHLIST, HomeSection.WATCHLIST_MOVIES, HomeSection.WATCHLIST_TV ->
                "Save movies and shows to your watchlist to see them here."
            HomeSection.UPCOMING_SCHEDULE -> "Upcoming episodes will appear here for the shows you follow."
            HomeSection.ADDON_SHELVES -> "Connect an addon with catalogs to see its titles here."
            HomeSection.MDBLIST_SHELVES -> "Choose MDBList lists in Settings to see their titles here."
            else -> "No titles are available for this row yet."
        }
        AppleTvShelf(row.key, row.title, items, row.section?.name.orEmpty(), message, row.presetId)
    }
}

internal val streamingServiceNames = mapOf(
    8 to "Netflix",
    9 to "Prime Video",
    337 to "Disney+",
    350 to "Apple TV+",
    1899 to "Max",
    15 to "Hulu",
    531 to "Paramount+",
    386 to "Peacock",
    283 to "Crunchyroll",
    11 to "Mubi",
    43 to "Starz",
    37 to "Showtime",
    380 to "BritBox",
    258 to "Criterion",
    73 to "Tubi",
    300 to "Pluto TV",
    613 to "Freevee",
    190 to "Curiosity Stream",
    439 to "Shudder",
    30 to "WOW",
    298 to "RTL+",
    421 to "Joyn",
    551 to "MagentaTV",
)
