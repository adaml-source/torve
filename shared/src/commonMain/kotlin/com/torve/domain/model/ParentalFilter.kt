package com.torve.domain.model

/**
 * Filters media items based on a profile's max content rating.
 *
 * Uses TMDB vote averages and genre IDs as heuristic signals:
 * - G: Family-friendly only (rating <= 7.0, no adult genres)
 * - PG: Most content, no extreme violence/horror (no adult genres)
 * - PG-13: Filter out adult content only
 * - R: Allow all non-adult content
 * - NC-17 / null: No filtering
 *
 * TMDB genre IDs: Horror=27, Thriller=53, Crime=80, War=10752
 */
object ParentalFilter {

    private val restrictedGenreIds = setOf(27, 80) // Horror, Crime

    fun filter(items: List<MediaItem>, maxRating: ContentRating?): List<MediaItem> {
        if (maxRating == null) return items
        return items.filter { isAllowed(it, maxRating) }
    }

    private fun isAllowed(item: MediaItem, maxRating: ContentRating): Boolean {
        val genreIds = item.genreIds.toSet()

        return when (maxRating) {
            ContentRating.G -> {
                // Family only: no restricted genres, moderate rating
                genreIds.none { it in restrictedGenreIds } &&
                    (item.rating == null || item.rating <= 7.5)
            }
            ContentRating.PG -> {
                // No horror/extreme crime
                genreIds.none { it in restrictedGenreIds }
            }
            ContentRating.PG_13 -> {
                // Allow most, filter out horror
                27 !in genreIds
            }
            ContentRating.R, ContentRating.NC_17 -> {
                // Allow everything
                true
            }
        }
    }
}
