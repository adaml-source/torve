package com.torve.android.tv.focus

internal enum class TvLibraryTab {
    WATCHLIST,
    FAVORITES,
    REQUESTS,
    VOD,
    DOWNLOADS,
}

/**
 * Stable tab/focus contract kept outside Compose so restored selections and
 * click-focus behavior can be regression-tested.
 */
internal object TvLibraryFocusPolicy {
    val visibleTabs = listOf(
        TvLibraryTab.WATCHLIST,
        TvLibraryTab.FAVORITES,
        TvLibraryTab.REQUESTS,
        TvLibraryTab.VOD,
    )

    fun normalizeIndex(index: Int): Int = index.coerceIn(0, visibleTabs.lastIndex)

    fun tabAt(index: Int): TvLibraryTab = visibleTabs[normalizeIndex(index)]

    fun shouldRestoreClickedTab(clickedIndex: Int?, selectedIndex: Int): Boolean =
        clickedIndex != null && normalizeIndex(clickedIndex) == normalizeIndex(selectedIndex)
}
