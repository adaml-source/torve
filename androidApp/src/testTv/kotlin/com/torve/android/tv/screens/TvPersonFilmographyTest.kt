package com.torve.android.tv.screens

import com.torve.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TvPersonFilmographyTest {
    private val film = MediaItem("42", tmdbId = 42, type = MediaType.MOVIE, title = "Film", creditIsCrew = true, creditJob = "Director")
    private val series = film.copy(id = "43", tmdbId = 43, type = MediaType.SERIES, title = "Series")
    @Test fun directorsKeepTheirCrewFilmographyInTheDefaultView() {
        assertEquals(listOf(film, series), tvPersonFilmography(listOf(film, series), "Directing", "roles", "popular"))
    }
    @Test fun movieAndTvFiltersRetainDirectorCredits() {
        assertEquals(listOf(film), tvPersonFilmography(listOf(film, series), "Directing", "movies", "popular"))
        assertEquals(listOf(series), tvPersonFilmography(listOf(film, series), "Directing", "tv", "popular"))
    }
    @Test fun actorsKeepSelfAppearancesSeparate() {
        val role = film.copy(creditIsCrew = false, creditCharacter = "A character")
        val appearance = series.copy(creditIsCrew = false, creditCharacter = "Self")
        assertEquals(listOf(role), tvPersonFilmography(listOf(role, appearance), "Acting", "roles", "popular"))
        assertEquals(listOf(appearance), tvPersonFilmography(listOf(role, appearance), "Acting", "other", "popular"))
    }
    @Test fun filmographyDoesNotRepeatATitleWithSeveralCrewJobs() {
        assertEquals(1, tvPersonFilmography(listOf(film, film.copy(creditJob = "Writer")), "Writing", "roles", "imdb").size)
    }
}
