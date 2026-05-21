package com.torve.domain.model

import kotlinx.serialization.Serializable

/**
 * All possible sections on the home screen.
 * Each has a default title, default enabled state, and default order.
 */
enum class HomeSection(
    val defaultTitle: String,
    val defaultEnabled: Boolean,
    val defaultOrder: Int,
) {
    SEARCH_BAR("Search Bar", true, -1),
    HERO("Featured", true, 0),
    ON_NOW("On now", true, 1),
    CONTINUE_WATCHING("Continue Watching", true, 1),
    UPCOMING_SCHEDULE("Upcoming Schedule", true, 2),
    WATCHLIST("My Watchlist", true, 2),
    WATCHLIST_MOVIES("Watchlist — Movies", false, 3),
    WATCHLIST_TV("Watchlist — TV Shows", false, 4),
    TRENDING_MOVIES("Trending Movies", true, 5),
    TRENDING_TV("Trending TV Shows", true, 6),
    POPULAR_MOVIES("Popular Movies", true, 7),
    NOW_PLAYING("Now Playing", true, 8),
    RECOMMENDED("Recommended For You", true, 9),
    NEW_RELEASES("Upcoming", true, 10),
    TOP_RATED("Top Rated", true, 11),
    STREAMING_SERVICES("Streaming Services", false, 12),
    RECENTLY_WATCHED("Recently Watched", true, 13),
    ACTORS("Popular Actors", true, 14),
    DIRECTORS("Popular Directors", false, 15),
    HIDDEN_GEMS("Hidden Gems", true, 16),
    ADDON_SHELVES("Addon Catalogs", false, 17),
    BECAUSE_YOU_WATCHED("Because You Watched", false, 18),
    MDBLIST_SHELVES("MDBList Lists", false, 19);

    /** Maps this section to its corresponding CatalogShelf id, if any. */
    val shelfId: String?
        get() = when (this) {
            TRENDING_MOVIES -> "trending-movies"
            TRENDING_TV -> "trending-tv"
            NOW_PLAYING -> "now-playing"
            POPULAR_MOVIES -> "popular-movies"
            NEW_RELEASES -> "upcoming"
            TOP_RATED -> "top-rated"
            else -> null
        }
}

/**
 * User configuration for a single home section.
 * Persisted via PreferencesRepository as JSON.
 */
@Serializable
data class HomeSectionConfig(
    val section: HomeSection,
    val enabled: Boolean,
    val order: Int,
    val customTitle: String? = null,
    val presetId: String? = null,
)

fun updateSectionPresetId(
    configs: List<HomeSectionConfig>,
    section: HomeSection,
    presetId: String?,
): List<HomeSectionConfig> = configs.map { config ->
    if (config.section == section) config.copy(presetId = presetId) else config
}

/** Returns true if this shelf matches the given HomeSection based on shelf id. */
fun CatalogShelf.matchesSection(section: HomeSection): Boolean =
    section.shelfId != null && id == section.shelfId
