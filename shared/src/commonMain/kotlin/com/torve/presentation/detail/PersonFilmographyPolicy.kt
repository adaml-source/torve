package com.torve.presentation.detail

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.presentation.catalog.highestImdbFirst

/** Keep uncertain credits visible; move only positively identified extras out of acting roles. */
object PersonFilmographyPolicy {
    private val appearanceGenres = setOf(99, 10763, 10764, 10767)
    private val selfRole = Regex("^(self|himself|herself|themselves)\\b", RegexOption.IGNORE_CASE)

    fun isAppearance(item: MediaItem): Boolean = item.creditIsCrew ||
        selfRole.containsMatchIn(item.creditCharacter.orEmpty().trim()) ||
        item.genreIds.any { it in appearanceGenres }

    fun items(credits: List<MediaItem>, filter: String, sort: String): List<MediaItem> {
        val selected = credits.filter {
            when (filter) {
                "movies" -> it.type == MediaType.MOVIE && !isAppearance(it)
                "tv" -> it.type == MediaType.SERIES && !isAppearance(it)
                "other" -> isAppearance(it)
                "all" -> true
                else -> !isAppearance(it)
            }
        }.distinctBy { "${it.type}:${it.tmdbId ?: it.id}" }
        return when (sort) {
            "imdb" -> selected.highestImdbFirst()
            "newest" -> selected.sortedWith(compareByDescending<MediaItem> { it.releaseDate.orEmpty() }
                .thenBy { it.title }.thenBy { it.id })
            "rating" -> selected.sortedWith(compareByDescending<MediaItem> { it.rating?.takeIf { v -> v > 0 } ?: -1.0 }
                .thenByDescending { it.voteCount ?: 0 }.thenBy { it.title }.thenBy { it.id })
            else -> selected.sortedWith(compareByDescending<MediaItem> { it.popularity ?: 0.0 }
                .thenBy { it.title }.thenBy { it.id })
        }
    }
}
