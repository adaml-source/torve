package com.torve.desktop.ui.v2.detail

import com.torve.domain.model.Genre
import com.torve.domain.model.MediaCompany
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovieDetailsPresentationTest {

    @Test
    fun locked_content_does_not_expose_backdrop_artwork() {
        val locked = movie(
            backdropUrl = "https://image.example/backdrop.jpg",
            posterUrl = "https://image.example/poster.jpg",
            isContentPlaceholder = true,
        )

        assertNull(movieBackdropUrl(locked))
    }

    @Test
    fun movie_details_rows_only_include_available_real_metadata() {
        val item = movie(
            status = "Released",
            releaseDate = "2025-06-20",
            runtime = 148,
            year = 2025,
            director = "Jane Director",
            studios = listOf(MediaCompany(id = 1, name = "Long Studio Name")),
            genres = listOf(Genre(id = 18, name = "Drama"), Genre(id = 878, name = "Science Fiction")),
        )

        assertEquals(
            listOf(
                MovieDetailInfoRowModel("Release Date", "2025-06-20"),
                MovieDetailInfoRowModel("Runtime", "148m"),
                MovieDetailInfoRowModel("Director", "Jane Director"),
                MovieDetailInfoRowModel("Studio", "Long Studio Name"),
                MovieDetailInfoRowModel("Genres", "Drama, Science Fiction"),
            ),
            movieDetailsInfoRows(item),
        )
    }

    @Test
    fun rating_chips_only_render_sources_that_have_values() {
        val item = movie(
            ratings = MediaRatings(
                imdbScore = 6.4f,
                tmdbScore = 4.6f,
                rottenTomatoesScore = 71,
            ),
        )

        assertEquals(
            listOf(
                MovieRatingChipModel("IMDb", "6.4/10"),
                MovieRatingChipModel("TMDB", "46%"),
                MovieRatingChipModel("RT", "71%"),
            ),
            movieRatingChips(item),
        )
    }

    @Test
    fun tmdb_rating_chip_can_use_movie_rating_fallback() {
        val item = movie(rating = 8.6)

        assertEquals(
            listOf(MovieRatingChipModel("TMDB", "86%")),
            movieRatingChips(item),
        )
    }

    private fun movie(
        backdropUrl: String? = null,
        posterUrl: String? = null,
        isContentPlaceholder: Boolean = false,
        status: String? = null,
        releaseDate: String? = null,
        runtime: Int? = null,
        year: Int? = null,
        director: String? = null,
        studios: List<MediaCompany> = emptyList(),
        genres: List<Genre> = emptyList(),
        ratings: MediaRatings? = null,
        rating: Double? = null,
    ): MediaItem = MediaItem(
        id = "movie-1",
        type = MediaType.MOVIE,
        title = "Test Movie",
        backdropUrl = backdropUrl,
        posterUrl = posterUrl,
        isContentPlaceholder = isContentPlaceholder,
        status = status,
        releaseDate = releaseDate,
        runtime = runtime,
        year = year,
        director = director,
        studios = studios,
        genres = genres,
        ratings = ratings,
        rating = rating,
    )
}
