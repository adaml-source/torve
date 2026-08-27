package com.torve.presentation.home

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

class BecauseYouWatchedShelvesTest {
    @Test
    fun groupsOnlyActuallyWatchedSourcesIntoOneMovieAndOneTvRail() {
        val movieA = watched("movie-a", 101, MediaType.MOVIE)
        val showA = watched("show-a", 201, MediaType.SERIES)
        val movieB = watched("movie-b", 102, MediaType.MOVIE)
        val showDuplicate = showA.copy(id = "show-a-duplicate")
        val unresolved = watched("unresolved", null, MediaType.MOVIE)

        val shelves = buildBecauseYouWatchedShelves(
            listOf(movieA, showA, movieB, showDuplicate, unresolved),
        )

        assertEquals(
            listOf(BECAUSE_YOU_WATCHED_MOVIES_ID, BECAUSE_YOU_WATCHED_TV_ID),
            shelves.map { it.id },
        )
        assertEquals(
            listOf(BECAUSE_YOU_WATCHED_MOVIES_TITLE, BECAUSE_YOU_WATCHED_TV_TITLE),
            shelves.map { it.title },
        )
        assertEquals(listOf(movieA, movieB), shelves[0].items)
        assertEquals(listOf(showA), shelves[1].items)
    }

    @Test
    fun omitsEmptyMediaTypeRail() {
        val shelves = buildBecauseYouWatchedShelves(
            listOf(watched("show", 202, MediaType.SERIES)),
        )

        assertEquals(listOf(BECAUSE_YOU_WATCHED_TV_ID), shelves.map { it.id })
    }

    private fun watched(id: String, tmdbId: Int?, type: MediaType) = MediaItem(
        id = id,
        tmdbId = tmdbId,
        type = type,
        title = id,
        posterUrl = "/$id.jpg",
    )
}
