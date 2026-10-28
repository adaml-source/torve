package com.torve.android.ui.community

/** Stable navigation identities keep service forms out of the category picker. */
data class SettingsMenuEntry(val id: String, val title: String, val summary: String = "",
    val action: String? = null, val children: List<SettingsMenuEntry> = emptyList())

object SettingsMenus {
    fun sources(tv: Boolean = false): List<SettingsMenuEntry> = listOf(
        SettingsMenuEntry("streaming", "Streaming & extensions", "Playback providers and subtitle services", children = listOf(
            SettingsMenuEntry("web_replays", "Sports Replays", "Websites, replay artwork and source choices", "web_replays"),
            SettingsMenuEntry("extensions", "Extensions", "Catalogues, metadata and playback", "extensions"),
            SettingsMenuEntry("debrid", "Streaming services", "Real-Debrid, AllDebrid, Premiumize and TorBox", "Connections"),
            SettingsMenuEntry("usenet_setup", "Usenet & NZB indexers", "News server, indexers and download client", "usenet_setup"),
            SettingsMenuEntry("nzbdav", "NzbDAV", "Connect your Usenet server", "NzbDAV"),
            SettingsMenuEntry("opensubtitles", "OpenSubtitles", "Subtitle service API key", "OpenSubtitles"))),
        SettingsMenuEntry("tracking", "Tracking accounts", "Sync watched history and progress", children = listOf(
            SettingsMenuEntry("trakt", "Trakt", "History, ratings and lists", "Trakt"),
            SettingsMenuEntry("simkl", "SIMKL", "Sync movies and shows", "SIMKL"))),
        SettingsMenuEntry("servers", "Media servers & requests", "Your own libraries and requests", children = listOf(
            SettingsMenuEntry("jellyfin", "Jellyfin", "Connect your library", "Jellyfin"),
            SettingsMenuEntry("plex", "Plex", "Connect your library", "Plex"),
            SettingsMenuEntry("seerr", "Seerr", "Manage requests", "Seerr"))),
        SettingsMenuEntry("lists", "Channels & collections", "Playlists, lists and saved franchises", children = listOf(
            SettingsMenuEntry("channels", "Channels", "Playlists and programme guide", "Channels"),
            SettingsMenuEntry("mdblist", "MDBList", "Manage your lists", "MDBList"),
            SettingsMenuEntry("collections", "Saved collections", "Keep franchises together", "collections"))),
    ) + if (tv) emptyList() else listOf(SettingsMenuEntry("setup", "Source setup", "Set up playback sources and connections", "setup"), SettingsMenuEntry("source_tools", "Source tools", "Advanced matching and library services", children = listOf(
        SettingsMenuEntry("omdb", "OMDb", "Additional ratings and full plots", "OMDb"),
        SettingsMenuEntry("groups", "Source groups", "Organize playback sources", "groups"),
        SettingsMenuEntry("patterns", "Source name patterns", "Match and clean source names", "patterns"),
        SettingsMenuEntry("kodi", "Kodi remotes", "Connect your Kodi devices", "kodi"),
        SettingsMenuEntry("automation", "Library automation", "Sonarr, Radarr and related services", "automation"),
        SettingsMenuEntry("content", "Content controls", "Sensitive content preferences", "content"))))

    /** Current source destinations, with Usenet directly discoverable. */
    fun sourceSetup(): List<SettingsMenuEntry> {
        val entries = sources()
        return listOf("usenet_setup", "nzbdav", "debrid", "extensions", "jellyfin", "plex")
            .mapNotNull { find(entries, it) }
    }

    fun advanced(): List<SettingsMenuEntry> = listOf(
        SettingsMenuEntry("ai", "AI Search", "Search assistant provider and API key", "ai"),
        SettingsMenuEntry("features", "App features", "Choose optional features", "features"),
        SettingsMenuEntry("backup", "Backup & restore", "Cloud versions and local files", "backup"),
        SettingsMenuEntry("health", "Connection tests", "Check configured services", "health"),
        SettingsMenuEntry("about", "About & help", "Version, support and diagnostics", "about"),
    )

    fun leafForAction(entries: List<SettingsMenuEntry>, action: String?): SettingsMenuEntry? =
        if (action == null) null else entries.firstNotNullOfOrNull {
            if (it.action == action) it else leafForAction(it.children, action)
        }

    fun find(entries: List<SettingsMenuEntry>, id: String): SettingsMenuEntry? = entries.firstNotNullOfOrNull {
        if (it.id == id) it else find(it.children, id)
    }
    fun parent(entries: List<SettingsMenuEntry>, id: String): SettingsMenuEntry? = entries.firstNotNullOfOrNull {
        if (it.children.any { child -> child.id == id }) it else parent(it.children, id)
    }
}
