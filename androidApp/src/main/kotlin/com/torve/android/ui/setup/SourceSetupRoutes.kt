package com.torve.android.ui.setup

/** Route compatibility belongs here, so restored links cannot reopen a retired screen. */
object SourceSetupRoutes {
    const val DIRECTORY = "source_setup"
    const val USENET = "usenet_setup"
    val directoryAliases = listOf(DIRECTORY, "setup_choice", "setup", "setup_guided", "manage_panda", "panda_setup")

    fun destination(action: String): String = when (action) {
        "usenet_setup" -> USENET
        "extensions" -> "extensions"
        "Connections" -> "integrations?service=debrid"
        "NzbDAV", "Jellyfin", "Plex" -> "integrations?service=${action.lowercase()}"
        else -> error("Unknown source setup action: $action")
    }
}
