package com.torve.domain.model

import kotlin.test.*

class CatalogGroupTest {
    private fun movie(id: String, tmdb: Int? = null) = MediaItem(id, tmdbId = tmdb, type = MediaType.MOVIE, title = id)
    @Test fun providerDuplicatesCollapseButMovieAndSeriesRemainDistinct() {
        val sources = listOf(CatalogGroupSource("a", "A", listOf(movie("provider:one", 1), movie("2", 2))),
            CatalogGroupSource("b", "B", listOf(movie("1", 1), movie("3", 3),
                movie("tv:1", 1).copy(type = MediaType.SERIES))))
        val merged = mergeCatalogGroup(CatalogGroup("g", "Group", listOf("a", "b")), sources)
        assertEquals(listOf("provider:one", "2", "3", "tv:1"), merged.map { it.id })
    }
    @Test fun sourcesAreInterleavedAndUnavailableCatalogsDoNotEraseOtherResults() {
        val sources = listOf(CatalogGroupSource("a", "A", listOf(movie("1"), movie("2"), movie("3"))),
            CatalogGroupSource("b", "B", listOf(movie("4"), movie("5"))))
        assertEquals(listOf("1", "4", "2", "5", "3"), mergeCatalogGroup(CatalogGroup("g", "G", listOf("missing", "a", "b")), sources).map { it.id })
    }
}
