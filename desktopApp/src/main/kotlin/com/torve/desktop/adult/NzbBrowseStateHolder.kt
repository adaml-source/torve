package com.torve.desktop.adult

/**
 * Process-scoped state cache for the three NZB-driven catalog pages
 * ([com.torve.desktop.ui.v2.adult.V2AdultPage],
 *  [com.torve.desktop.ui.v2.sports.V2SportsPage],
 *  [com.torve.desktop.ui.v2.nzbmovies.V2NzbMoviesPage]).
 *
 * The composables themselves use `remember`, which is scoped to the
 * Compose call site - navigating away (e.g. when the user taps Play and
 * the playback overlay swaps in) tears down the call site and discards
 * the search query and result list. Storing it here, in plain JVM
 * memory, lets the page rehydrate the same query + results + scroll
 * offset on return without round-tripping the indexer.
 *
 * Cleared automatically on app restart (singleton in classloader memory).
 */
object NzbBrowseStateHolder {
    data class State(
        val query: String = "",
        val items: List<NewznabItem> = emptyList(),
        val errorText: String? = null,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        val selectedCategories: Set<String> = emptySet(),
        val selectedSportBucket: String? = null,
        val selectedLanguages: Set<String> = emptySet(),
    )

    private val byPage = mutableMapOf<String, State>()

    fun get(pageKey: String): State = byPage[pageKey] ?: State()

    fun put(pageKey: String, state: State) {
        byPage[pageKey] = state
    }

    fun clear(pageKey: String) {
        byPage.remove(pageKey)
    }
}
