package com.torve.android.tv.screens

import com.torve.android.tv.components.TvContentRail
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.repository.MetadataRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvCatalogDiscoveryParityTest {
    private fun specs(type: String, extras: Boolean = true) = catalogRailSpecs(type,
        catalogDiscoveryGenres(type), "Trending", "Popular", "Top Rated", extras)

    @Test fun moviesUseIosRailNamesAndSequenceIncludingMissingMusicAndTvMovieGenres() {
        val rows = specs("movie")
        assertEquals(listOf("Trending", "Popular", "Top Rated", "Upcoming", "Action", "Adventure",
            "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History",
            "Horror", "Music", "Mystery", "Romance", "Science Fiction", "TV Movie", "Thriller", "War", "Western"), rows.map { it.title })
        assertEquals(listOf("trending_movie", "popular_movie", "top_rated_movie", "upcoming"), rows.take(4).map { it.key })
        assertEquals(10402, rows.first { it.title == "Music" }.genreId)
        assertEquals(10770, rows.first { it.title == "TV Movie" }.genreId)
    }

    @Test fun showsUseIosGenresWithoutCalendarOrAiringToday() {
        assertEquals(listOf("Trending", "Popular", "Top Rated", "Action & Adventure", "Animation", "Comedy",
            "Crime", "Documentary", "Drama", "Family", "Kids", "Mystery", "News", "Reality", "Sci-Fi & Fantasy",
            "Soap", "Talk", "War & Politics", "Western"), specs("tv").map { it.title })
        assertEquals("genre_tv_10759", specs("tv")[3].key)
        assertTrue(specs("tv").none { "upcoming" in it.key || "airing" in it.key })
    }

    @Test fun kidsKeepTheSameThreeDiscoveryShelvesAsIos() {
        for (type in listOf("movie", "tv")) {
            assertEquals(listOf("Trending", "Popular", "Top Rated"), specs(type, false).map { it.title })
            assertTrue(catalogDiscoveryGenres(type).isNotEmpty()) // Genre shortcuts remain available on iOS.
        }
    }

    @Test fun legacyCacheCannotRestoreObsoleteRowsNamesOrOrdering() {
        val item = MediaItem("42", type = MediaType.MOVIE, title = "A movie")
        fun row(key: String) = TvContentRail(key, "Obsolete label", listOf(item))
        val aligned = alignCatalogDiscoveryRails(listOf(row("genre_movie_18"), row("now-playing"),
            row("popular_movie"), row("trending_movie"), row("upcoming_schedule_tv"), row("genre_movie_10402")), specs("movie"))
        assertEquals(listOf("Trending", "Popular", "Drama", "Music"), aligned.map { it.title })
        assertTrue(aligned.all { it.items.single() === item })
    }

    @Test fun slowEarlierRailUsesIosOrderWithoutReplacingExistingMedia() {
        val movie = MediaItem("42", type = MediaType.MOVIE, title = "Keep focus")
        val popular = TvContentRail("popular_movie", "Popular", listOf(movie))
        val trending = popular.copy(key = "trending_movie", title = "Trending")
        val merged = mergeProgressiveCatalogRows(listOf(popular), listOf(trending)) { it.key }
        val rows = alignCatalogDiscoveryRails(merged, specs("movie"))
        assertEquals(listOf("trending_movie", "popular_movie"), rows.map { it.key })
        assertEquals(popular, rows.first { it.key == popular.key })
    }

    @Test fun topRatedFetchUsesIosSourceAndKeepsTitlesBeforeExternalRatingsArrive() = runTest {
        val calls = mutableListOf<String>()
        val item = MediaItem("42", type = MediaType.MOVIE, title = "Top rated with no cached IMDb yet")
        val repository = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MetadataRepository::class.java)) { _, method, _ ->
            calls += method.name
            check(method.name == "getTopRated") { "Wrong source: ${method.name}" }
            listOf(item)
        } as MetadataRepository
        for (type in listOf("movie", "tv")) {
            val spec = specs(type).first { it.special == "top_rated" }
            val items = loadCatalogRailItems(repository, type, spec, firstPageOnly = true)
            assertEquals(listOf(item), items)
            assertEquals(items, alignCatalogDiscoveryRails(listOf(TvContentRail(spec.key, spec.title, items)), specs(type)).single().items)
        }
        assertEquals(listOf("getTopRated", "getTopRated"), calls)
    }
}
