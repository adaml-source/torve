package com.torve.presentation.search

import com.torve.domain.model.*
import com.torve.presentation.catalog.*
import kotlin.test.*

class SearchFilterMatchingTest {
    private val film = MediaItem("1", type = MediaType.MOVIE, title = "Example", year = 2010, runtime = 120,
        rating = 8.2, genreIds = listOf(80), ageCertifications = listOf(AgeCertification("US", "PG-13")),
        ratings = MediaRatings(imdbScore = 8.4f))
    @Test fun genreYearReviewRuntimeAndCertificationAllHaveToMatch() {
        val filter = SearchFilter(mediaType = "movie", genreIds = listOf(28, 80), yearFrom = 2000, yearTo = 2020,
            minImdbScore = 8f, minTmdbScore = 8f, runtimeFilter = RuntimeFilter.STANDARD, certification = "PG-13")
        assertTrue(filter.matches(film))
        assertFalse(filter.matches(film.copy(runtime = null)))
        assertFalse(filter.matches(film.copy(year = null)))
        assertFalse(filter.matches(film.copy(ratings = null)))
        assertFalse(filter.matches(film.copy(type = MediaType.SERIES)))
        assertFalse(filter.matches(film.copy(ageCertifications = listOf(AgeCertification("GB", "12A")))))
    }
    @Test fun runtimeBucketsDoNotOverlapAndRatingIsNotACertification() {
        assertFalse(SearchFilter(runtimeFilter = RuntimeFilter.SHORT).matches(film.copy(runtime = 90)))
        assertTrue(SearchFilter(runtimeFilter = RuntimeFilter.STANDARD).matches(film.copy(runtime = 150)))
        assertFalse(SearchFilter(runtimeFilter = RuntimeFilter.LONG).matches(film.copy(runtime = 150)))
        assertFalse(SearchFilter(certification = "PG-13").matches(film.copy(ageCertifications = emptyList(), rating = 10.0)))
    }
    @Test fun allGenresMapAcrossMoviesAndShowsAndUnknownProviderDataDoesNotMaskTmdb() {
        val tv = film.copy(type = MediaType.SERIES, genreIds = listOf(10759), ratings = MediaRatings(tmdbScore = 0f))
        assertTrue(SearchFilter(genreIds = listOf(28), minTmdbScore = 8f).matches(tv))
        assertFalse(SearchFilter(mediaType = "movie", genreIds = listOf(28)).matches(tv))
    }
    @Test fun newestAndOldestWorkForTextSearchAndActiveCountCountsACertificationOnce() {
        val old = film.copy(id = "2", year = 1990)
        assertEquals(listOf(film, old), SearchFilter(sortBy = SortOption.RELEASE_DATE_DESC).order(listOf(old, film)))
        assertEquals(listOf(old, film), SearchFilter(sortBy = SortOption.RELEASE_DATE_ASC).order(listOf(film, old)))
        assertEquals(1, SearchFilter(certification = "G").activeCount)
    }
}
