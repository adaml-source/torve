package com.torve.android.tv.focus

internal enum class TvLibraryTab {
    WATCHLIST,
    FAVORITES,
    REQUESTS,
    VOD,
    DOWNLOADS,
    CONTINUE,
    HISTORY,
}

/**
 * Stable tab/focus contract kept outside Compose so restored selections and
 * click-focus behavior can be regression-tested.
 */
internal object TvLibraryFocusPolicy {
    fun tabsForFeatures(features: com.torve.presentation.settings.OptionalAppFeatures, loaded: Boolean = true): List<TvLibraryTab> = visibleTabs
    fun restoreTab(key: String, tabs: List<TvLibraryTab>): TvLibraryTab =
        tabs.firstOrNull { it.name == key } ?: TvLibraryTab.WATCHLIST
    fun restoreCanonicalTab(index: Int, tabs: List<TvLibraryTab>): TvLibraryTab = restoreTab(tabAt(index).name, tabs)
    val visibleTabs = listOf(
        TvLibraryTab.WATCHLIST,
        TvLibraryTab.FAVORITES,
        TvLibraryTab.CONTINUE,
        TvLibraryTab.HISTORY,
    )

    fun normalizeIndex(index: Int): Int = index.coerceIn(0, visibleTabs.lastIndex)

    fun tabAt(index: Int): TvLibraryTab = visibleTabs[normalizeIndex(index)]

    fun shouldRestoreClickedTab(clickedIndex: Int?, selectedIndex: Int): Boolean =
        clickedIndex != null && normalizeIndex(clickedIndex) == normalizeIndex(selectedIndex)
}
