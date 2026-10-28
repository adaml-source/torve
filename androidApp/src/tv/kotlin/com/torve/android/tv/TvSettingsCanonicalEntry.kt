package com.torve.android.tv

/** External entries select a canonical form; they never wait for the retired settings row registry. */
internal fun canonicalTvSettingsEntryAction(itemId: String): String {
    val id = itemId.lowercase()
    return when {
        "trakt" in id -> "Trakt"
        "simkl" in id -> "SIMKL"
        "jellyfin" in id -> "Jellyfin"
        "plex" in id -> "Plex"
        "opensubtitles" in id -> "OpenSubtitles"
        "omdb" in id -> "OMDb"
        "debrid" in id -> "Connections"
        "pair" in id || "import" in id || "account" in id -> "account"
        "about" in id || "support" in id -> "about"
        "library" in id || "playlist" in id -> "Channels"
        "playback" in id -> "Playback"
        "appearance" in id -> "Display"
        else -> "tests"
    }
}
