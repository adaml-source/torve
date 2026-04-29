package com.torve.desktop.ui.navigation

enum class DesktopDestination(
    val label: String,
    val subtitle: String,
    val iconGlyph: String,
    val showInNav: Boolean = true,
) {
    HOME(
        label = "Home",
        subtitle = "Pick up where you left off.",
        iconGlyph = "H",
    ),
    MOVIES(
        label = "Movies",
        subtitle = "Browse and discover movies.",
        iconGlyph = "M",
    ),
    TV_SHOWS(
        label = "Shows",
        subtitle = "Browse and discover TV shows.",
        iconGlyph = "TV",
    ),
    SEARCH(
        label = "Search",
        subtitle = "Find a movie or series.",
        iconGlyph = "S",
    ),
    LIBRARY(
        label = "Library",
        subtitle = "Continue watching, watchlist, and history.",
        iconGlyph = "L",
    ),
    LIVE_TV(
        label = "Live",
        subtitle = "Channels and live playback.",
        iconGlyph = "LV",
    ),
    SETTINGS(
        label = "Settings",
        subtitle = "Account, services, and playback.",
        iconGlyph = "\u2699",
        showInNav = false,
    ),
}
