package com.streamvault.domain.model

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
    HERO("Featured", true, 0),
    CONTINUE_WATCHING("Continue Watching", true, 1),
    WATCHLIST("My Watchlist", true, 2),
    TRENDING_MOVIES("Trending Movies", true, 3),
    TRENDING_TV("Trending TV Shows", true, 4),
    POPULAR_MOVIES("Popular Movies", true, 5),
    NOW_PLAYING("Now Playing", true, 6),
    RECOMMENDED("Recommended For You", true, 7),
    NEW_RELEASES("Upcoming", true, 8),
    TOP_RATED("Top Rated", true, 9),
    STREAMING_SERVICES("Streaming Services", false, 10),
    RECENTLY_WATCHED("Recently Watched", true, 11),
    ACTORS("Popular Actors", true, 12),
    DIRECTORS("Popular Directors", true, 13),
    HIDDEN_GEMS("Hidden Gems", true, 14);

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
    val orientation: PosterOrientation = PosterOrientation.PORTRAIT,
    val size: PosterSize = PosterSize.MEDIUM,
)

/** Returns true if this shelf matches the given HomeSection based on shelf id. */
fun CatalogShelf.matchesSection(section: HomeSection): Boolean =
    section.shelfId != null && id == section.shelfId

/** Poster card orientation preference. */
enum class PosterOrientation { PORTRAIT, LANDSCAPE }

/** Poster card size preference. */
enum class PosterSize { SMALL, MEDIUM, LARGE }

