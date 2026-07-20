package com.torve.metadata

import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbCast
import com.torve.data.metadata.TmdbCredits
import com.torve.data.metadata.TmdbMappers
import com.torve.data.metadata.TmdbMovie
import com.torve.data.metadata.TmdbMultiResult
import com.torve.data.metadata.TmdbTv
import com.torve.data.metadata.TmdbVideo
import com.torve.data.metadata.TmdbVideos
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbMappersTest {

    @Test
    fun posterUrl_validPath() {
        val result = TmdbMappers.posterUrl("/abc123.jpg")
        assertEquals("${TmdbApiClient.IMAGE_BASE}/w500/abc123.jpg", result)
    }

    @Test
    fun posterUrl_nullPath() {
        assertNull(TmdbMappers.posterUrl(null))
    }

    @Test
    fun movieToMediaItem_basic() {
        val movie = TmdbMovie(
            id = 123,
            title = "Test Movie",
            overview = "A test movie",
            posterPath = "/poster.jpg",
            backdropPath = "/backdrop.jpg",
            voteAverage = 7.5,
            voteCount = 1000,
            releaseDate = "2024-06-15",
            genreIds = listOf(28, 12),
            popularity = 50.0,
            runtime = 135,
            imdbId = "tt1234567",
        )

        val result = TmdbMappers.movieToMediaItem(movie)

        assertEquals("123", result.id)
        assertEquals(123, result.tmdbId)
        assertEquals("tt1234567", result.imdbId)
        assertEquals(MediaType.MOVIE, result.type)
        assertEquals("Test Movie", result.title)
        assertEquals(2024, result.year)
        assertEquals("A test movie", result.overview)
        assertEquals(7.5, result.rating)
        assertEquals(135, result.runtime)
    }

    @Test
    fun movieToMediaItem_extractsTrailer() {
        val movie = TmdbMovie(
            id = 1,
            title = "Movie",
            videos = TmdbVideos(
                results = listOf(
                    TmdbVideo(key = "abc", site = "Vimeo", type = "Trailer"),
                    TmdbVideo(key = "xyz", site = "YouTube", type = "Trailer"),
                    TmdbVideo(key = "def", site = "YouTube", type = "Teaser"),
                ),
            ),
        )

        val result = TmdbMappers.movieToMediaItem(movie)
        assertEquals("xyz", result.trailerKey)
    }

    @Test
    fun movieToMediaItem_castLimitedTo20() {
        val cast = (1..30).map { TmdbCast(id = it, name = "Actor $it", character = "Char $it") }
        val movie = TmdbMovie(
            id = 1,
            title = "Movie",
            credits = TmdbCredits(cast = cast),
        )

        val result = TmdbMappers.movieToMediaItem(movie)
        assertEquals(20, result.cast.size)
    }

    @Test
    fun tvToMediaItem_basic() {
        val tv = TmdbTv(
            id = 456,
            name = "Test Show",
            overview = "A test show",
            firstAirDate = "2023-01-01",
            voteAverage = 8.2,
        )

        val result = TmdbMappers.tvToMediaItem(tv)

        assertEquals("456", result.id)
        assertEquals(MediaType.SERIES, result.type)
        assertEquals("Test Show", result.title)
        assertEquals(2023, result.year)
    }

    @Test
    fun multiToMediaItem_movie() {
        val multi = TmdbMultiResult(
            id = 789,
            mediaType = "movie",
            title = "Multi Movie",
            releaseDate = "2024-03-15",
            voteAverage = 6.5,
        )

        val result = TmdbMappers.multiToMediaItem(multi)
        assertEquals(MediaType.MOVIE, result.type)
        assertEquals("Multi Movie", result.title)
    }

    @Test
    fun multiToMediaItem_tv() {
        val multi = TmdbMultiResult(
            id = 101,
            mediaType = "tv",
            name = "TV Show",
            firstAirDate = "2022-12-01",
        )

        val result = TmdbMappers.multiToMediaItem(multi)
        assertEquals(MediaType.SERIES, result.type)
        assertEquals("TV Show", result.title)
        assertEquals(2022, result.year)
    }

    @Test
    fun movieToMediaItem_nullDate() {
        val movie = TmdbMovie(id = 1, title = "No Date", releaseDate = null)
        val result = TmdbMappers.movieToMediaItem(movie)
        assertNull(result.year)
    }
}
