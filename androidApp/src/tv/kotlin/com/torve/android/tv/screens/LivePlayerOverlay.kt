package com.torve.android.tv.screens

/**
 * Overlay states for the TiviMate-style live TV player.
 */
enum class LivePlayerOverlay {
    /** No overlay — full-screen video only. */
    NONE,

    /** Channel info HUD: programme data, clock, quality badges, recent/favourite cards. */
    CHANNEL_INFO,

    /** Bottom menu toolbar: stream info row + action buttons. */
    MENU_BAR,

    /** Layered playback utility menu opened by the remote Menu key. */
    PLAYBACK_MENU,

    /** Current-channel schedule with replay actions and a path to the full guide. */
    CURRENT_CHANNEL_GUIDE,

    /** Full-screen EPG programme grid. */
    EPG_GUIDE,

    /** Two-panel channel browser (groups + channels). */
    CHANNEL_LIST,

    /** Settings panel: playlists, countries, favourites, categories, adult filter. */
    SETTINGS_PANEL,
}
