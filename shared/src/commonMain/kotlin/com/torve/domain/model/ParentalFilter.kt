package com.torve.domain.model

/** Age limits use verified classifications. Review scores and genres are never age evidence. */
object ParentalFilter {
    fun maximumAge(rating: ContentRating): Int = when (rating) {
        ContentRating.G -> 0
        ContentRating.PG -> 8
        ContentRating.PG_13 -> 13
        ContentRating.R -> 17
        ContentRating.NC_17 -> 18
    }
    fun filter(items: List<MediaItem>, maxRating: ContentRating?): List<MediaItem> {
        if (maxRating == null || maxRating == ContentRating.NC_17) return items
        val rules = KidsProfileRules(maximumAge = maximumAge(maxRating), ratingCountry = "US", hideUnrated = true)
        return items.filter { FamilyPolicy.allows(it, rules) }
    }
}
