package com.torve.presentation.catalog

import com.torve.data.metadata.TmdbKeyword
import com.torve.data.metadata.TmdbPerson
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PagedResult
import com.torve.domain.model.PersonSummary
import com.torve.domain.model.Season
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogViewModelRegressionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun moviesLoadsContentForEligibleUser() = runTest(dispatcher) {
        val movieItem = mediaItem(id = "movie-1", title = "Movie A", type = MediaType.MOVIE)
        val viewModel = CatalogViewModel(
            metadataRepo = FakeMetadataRepository(
                trendingMoviePage = PagedResult(items = listOf(movieItem), page = 1, totalPages = 1, totalResults = 1),
            ),
            mediaType = "movie",
        )

        advanceUntilIdle()

        assertEquals(listOf(movieItem), viewModel.state.value.items)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun tvShowsLoadContentForEligibleUser() = runTest(dispatcher) {
        val showItem = mediaItem(id = "show-1", title = "Show A", type = MediaType.SERIES)
        val viewModel = CatalogViewModel(
            metadataRepo = FakeMetadataRepository(
                trendingTvPage = PagedResult(items = listOf(showItem), page = 1, totalPages = 1, totalResults = 1),
            ),
            mediaType = "tv",
        )

        advanceUntilIdle()

        assertEquals(listOf(showItem), viewModel.state.value.items)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(null, viewModel.state.value.error)
    }

    @Test
    fun contentLoaderSurfacesErrorStateWhenRepositoryFails() = runTest(dispatcher) {
        val viewModel = CatalogViewModel(
            metadataRepo = FakeMetadataRepository(
                trendingFailure = IllegalStateException("catalog backend down"),
            ),
            mediaType = "movie",
        )

        advanceUntilIdle()

        assertEquals("Movies could not be loaded. Please try again.", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    private class FakeMetadataRepository(
        private val trendingMoviePage: PagedResult = PagedResult(emptyList(), 1, 1, 0),
        private val trendingTvPage: PagedResult = PagedResult(emptyList(), 1, 1, 0),
        private val trendingFailure: Throwable? = null,
    ) : MetadataRepository {
        override suspend fun getTrending(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getPopular(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getTopRated(type: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun getUpcoming(page: Int): List<MediaItem> = emptyList()
        override suspend fun getNowPlaying(page: Int): List<MediaItem> = emptyList()
        override suspend fun getAiringToday(page: Int): List<MediaItem> = emptyList()
        override suspend fun searchMulti(query: String, page: Int): List<MediaItem> = emptyList()
        override suspend fun findByImdbId(imdbId: String, preferredType: String?): MediaItem? = null
        override suspend fun getDetail(type: String, id: Int): MediaItem = error("unused")
        override suspend fun getSimilar(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
        override suspend fun getRecommendations(type: String, id: Int, page: Int): List<MediaItem> = emptyList()
        override suspend fun getHomeShelves(): List<CatalogShelf> = emptyList()
        override suspend fun getPersonCredits(personId: Int): List<MediaItem> = emptyList()
        override suspend fun getPersonDetail(personId: Int): TmdbPerson = error("unused")
        override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): Season = error("unused")

        override suspend fun getTrendingPaged(type: String, page: Int): PagedResult {
            trendingFailure?.let { throw it }
            return if (type == "tv") trendingTvPage else trendingMoviePage
        }

        override suspend fun getPopularPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getTopRatedPaged(type: String, page: Int): PagedResult = PagedResult(emptyList(), 1, 1, 0)
        override suspend fun discover(
            type: String,
            page: Int,
            sortBy: String,
            withGenres: String?,
            minRating: Float?,
            year: Int?,
            yearTo: Int?,
            runtimeGte: Int?,
            runtimeLte: Int?,
            originCountries: String?,
            originalLanguage: String?,
            certification: String?,
            certificationGte: String?,
            certificationLte: String?,
            certificationCountry: String?,
            withCast: String?,
            withCrew: String?,
            withWatchProviders: String?,
            watchRegion: String?,
            withKeywords: String?,
        ): PagedResult = PagedResult(emptyList(), 1, 1, 0)

        override suspend fun searchKeywords(query: String): List<TmdbKeyword> = emptyList()
        override suspend fun searchMultiPaged(query: String, page: Int, type: String?): PagedResult =
            PagedResult(emptyList(), 1, 1, 0)
        override suspend fun getPopularPeople(page: Int): List<PersonSummary> = emptyList()
        override suspend fun searchPerson(query: String, page: Int): List<PersonSummary> = emptyList()
        override suspend fun getWatchProviderLogos(type: String, region: String): Map<Int, String> = emptyMap()
        override suspend fun getLogoUrl(type: String, tmdbId: Int): String? = null
    }

    private fun mediaItem(id: String, title: String, type: MediaType): MediaItem {
        return MediaItem(
            id = id,
            tmdbId = id.substringAfterLast('-').toIntOrNull(),
            title = title,
            type = type,
        )
    }
}
