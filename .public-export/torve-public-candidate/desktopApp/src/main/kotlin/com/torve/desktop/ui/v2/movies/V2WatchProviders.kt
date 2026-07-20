package com.torve.desktop.ui.v2.movies

/**
 * Curated list of major streaming providers exposed as filter chips on
 * the Movies and TV Shows pages. IDs are TMDB watch-provider IDs from
 * https://api.themoviedb.org/3/watch/providers/movie.
 *
 * The TMDB discover endpoint accepts `with_watch_providers` (a single
 * id or pipe-separated list) plus `watch_region`. We default the
 * region to "US" because most provider IDs are region-scoped and the
 * top providers all index well there. Region-aware UI is a follow-up.
 */
internal data class WatchProviderChip(val id: Int, val label: String)

internal val DESKTOP_WATCH_PROVIDERS: List<WatchProviderChip> = listOf(
    WatchProviderChip(8, "Netflix"),
    WatchProviderChip(337, "Disney+"),
    WatchProviderChip(1899, "Max"),
    WatchProviderChip(350, "Apple TV+"),
    WatchProviderChip(9, "Prime Video"),
    WatchProviderChip(15, "Hulu"),
    WatchProviderChip(531, "Paramount+"),
    WatchProviderChip(386, "Peacock"),
    WatchProviderChip(2, "Apple TV"),
    WatchProviderChip(283, "Crunchyroll"),
)
