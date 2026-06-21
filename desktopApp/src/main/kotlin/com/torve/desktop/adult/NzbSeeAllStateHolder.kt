package com.torve.desktop.adult

import com.torve.desktop.adult.IndexerCategoryMap.MovieLanguage

/**
 * Process-scoped persistence for the V2NzbSeeAllPage filter UI. The
 * matched-release list itself lives in [NzbCatalogService] (already
 * shared across compositions); this holder covers the *local* filter
 * widgets (query, language, year, genre, scroll offset) that would
 * otherwise reset to defaults each time the user opens a detail page
 * and navigates back.
 *
 * Single-instance because the see-all page is only opened via a unique
 * see-all sectionId (`LATEST_ON_USENET`); no need for a per-page-key
 * map like [NzbBrowseStateHolder] uses.
 */
object NzbSeeAllStateHolder {
    data class State(
        val query: String = "",
        val language: MovieLanguage = MovieLanguage.ANY,
        val yearFilter: Int? = null,
        val genreFilter: Int? = null,
        /** Inclusive minimum rating on a 0-10 scale. Items whose
         *  best-available rating falls below this are hidden. `null`
         *  means no rating filter. */
        val minRating: Float? = null,
        /** When set, only items with a rating from this source are
         *  shown. Lets users find titles with verified IMDb / RT / etc.
         *  scores rather than the TMDB-only fallback. */
        val requiredRatingSource: com.torve.domain.model.RatingSource? = null,
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
    )

    @Volatile
    private var state: State = State()

    fun get(): State = state

    fun put(newState: State) {
        state = newState
    }

    fun reset() {
        state = State()
    }
}
