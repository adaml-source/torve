package com.torve.android.tv.settings

/** Global override preserves each profile's page and row choices underneath it. */
data class TvFocusPreviewPolicy(val global: Boolean = false, val choices: Map<String, Boolean> = emptyMap()) {
    fun enabled(page: String, row: String? = null): Boolean = global ||
        (row?.let { choices["row:$page:$it"] } ?: choices["page:$page"] ?: false)
    fun hasPreview(page: String): Boolean = enabled(page) || choices.any { (key, value) -> value && key.startsWith("row:$page:") }
}

fun tvFocusPreviewPage(screen: String): String = when {
    screen.startsWith("library_jellyfin") -> "jellyfin"
    screen.startsWith("library") -> "library"
    screen.startsWith("tv_search") -> "search"
    screen.startsWith("see_all:person_") -> "people"
    screen.startsWith("see_all") || screen.startsWith("provider_catalog") || screen.startsWith("catalog_group") -> "seeAll"
    screen.startsWith("details") -> "related"
    screen.startsWith("extension_catalog_movie") -> "movies"
    screen.startsWith("extension_catalog") -> "shows"
    else -> screen
}

val TV_FOCUS_PREVIEW_PAGES = listOf("home" to "Home", "movies" to "Movies", "shows" to "TV shows",
    "library" to "Library", "search" to "Search", "jellyfin" to "Jellyfin", "seeAll" to "See All",
    "related" to "Related titles", "people" to "Filmographies")
