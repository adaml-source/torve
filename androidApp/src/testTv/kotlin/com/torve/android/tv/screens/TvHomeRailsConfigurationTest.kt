package com.torve.android.tv.screens

import com.torve.android.tv.components.TvCardStyle
import com.torve.android.ui.home.ALL_STREAMING_SERVICES
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.PersonSummary
import com.torve.presentation.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeRailsConfigurationTest {
    @Test
    fun enabledSparseRailRemainsVisible() {
        val rails = buildTvHomeRails(
            state = HomeUiState(
                shelves = listOf(
                    CatalogShelf(
                        id = "trending-movies",
                        title = "Trending Movies",
                        items = listOf(media("movie-1")),
                    ),
                ),
            ),
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.TRENDING_MOVIES, enabled = true, order = 0),
            ),
            customSections = emptyList(),
            homeLayoutOrder = listOf("section:TRENDING_MOVIES"),
        )

        assertEquals(listOf("trending-movies"), rails.map { it.key })
    }

    @Test
    fun upcomingAndPeopleSectionsRenderWhenEnabled() {
        val rails = buildTvHomeRails(
            state = HomeUiState(
                upcomingSchedule = listOf(media("episode-1", MediaType.SERIES)),
                popularActors = listOf(PersonSummary(7, "Actor", "/actor.jpg", "Acting")),
            ),
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.UPCOMING_SCHEDULE, enabled = true, order = 0),
                HomeSectionConfig(HomeSection.ACTORS, enabled = true, order = 1),
            ),
            customSections = emptyList(),
            homeLayoutOrder = listOf("section:UPCOMING_SCHEDULE", "section:ACTORS"),
        )

        assertEquals(listOf("upcoming_schedule", "popular_actors"), rails.map { it.key })
        assertTrue(rails.last().items.single().id.startsWith("person:"))
    }

    @Test
    fun addonRailsFollowParentSectionToggle() {
        val state = HomeUiState(
            addonShelves = listOf(
                CatalogShelf("addon-one", "Addon", listOf(media("addon-item"))),
            ),
        )
        val disabled = buildTvHomeRails(
            state,
            listOf(HomeSectionConfig(HomeSection.ADDON_SHELVES, enabled = false, order = 0)),
            emptyList(),
            listOf("section:ADDON_SHELVES", "addon:addon-one"),
        )
        val enabled = buildTvHomeRails(
            state,
            listOf(HomeSectionConfig(HomeSection.ADDON_SHELVES, enabled = true, order = 0)),
            emptyList(),
            listOf("section:ADDON_SHELVES", "addon:addon-one"),
        )

        assertFalse(disabled.any { it.key == "addon:addon-one" })
        assertTrue(enabled.any { it.key == "addon:addon-one" })
    }

    @Test
    fun streamingServicesRenderSavedProvidersFirstAndKeepFullCatalog() {
        val rails = buildTvHomeRails(
            state = HomeUiState(),
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.STREAMING_SERVICES, enabled = true, order = 0),
            ),
            customSections = emptyList(),
            homeLayoutOrder = listOf("section:STREAMING_SERVICES"),
            enabledStreamingServiceIds = setOf(8, 337),
            providerLogos = mapOf(8 to "netflix.png", 337 to "disney.png"),
        )

        val services = rails.single()
        assertEquals("streaming_services", services.key)
        assertEquals(TvCardStyle.SERVICE, services.cardStyle)
        assertEquals(listOf("provider:8", "provider:337"), services.items.take(2).map { it.id })
        assertEquals(listOf("netflix.png", "disney.png"), services.items.take(2).map { it.posterUrl })
        assertTrue(services.items.size > 6)
        assertTrue(services.items.any { it.id == "provider:531" })
        assertTrue(services.items.any { it.id == "provider:386" })
        assertTrue(services.items.any { it.id == "provider:283" })
        assertEquals(
            ALL_STREAMING_SERVICES.size,
            ALL_STREAMING_SERVICES.map { it.tmdbProviderId }.distinct().size,
        )
        assertFalse(services.items.any { it.id == "provider:613" })
        assertFalse(services.items.any { it.id == "provider:37" })
        assertTrue(services.items.any { it.title == "HBO Max" })
    }

    @Test
    fun becauseYouWatchedUsesExplicitSourceAsFirstPosterBeforeRecommendations() {
        val watchedMovie = media("watched-movie").copy(tmdbId = 101)
        val suggestion = media("suggestion").copy(tmdbId = 303)
        val rails = buildTvHomeRails(
            state = HomeUiState(
                becauseYouWatched = listOf(
                    CatalogShelf(
                        id = "because_movie_101",
                        title = "Because You Watched",
                        items = listOf(watchedMovie, suggestion),
                        sourceItem = watchedMovie,
                    ),
                ),
            ),
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED, enabled = true, order = 0),
            ),
            customSections = emptyList(),
            homeLayoutOrder = listOf("section:BECAUSE_YOU_WATCHED"),
        )

        val rail = rails.single()
        assertEquals("because_movie_101", rail.key)
        assertEquals(TV_HOME_BECAUSE_YOU_WATCHED_TITLE, rail.title)
        assertEquals(listOf(watchedMovie, suggestion), rail.items)
        assertFalse(rail.showSeeAllCard)
        assertTrue(rail.allowCrossRailDuplicates)
        assertEquals("more_like_movie_101", watchedMovie.tvMoreLikeRailKey())
    }

    @Test
    fun multipleBecauseYouWatchedSetsRemainIndependentBesideRecentlyWatched() {
        val watchedMovie = media("watched-movie").copy(tmdbId = 101)
        val watchedSeries = media("watched-series", MediaType.SERIES).copy(tmdbId = 202)
        val movieSuggestion = media("movie-suggestion").copy(tmdbId = 301)
        val seriesSuggestion = media("series-suggestion", MediaType.SERIES).copy(tmdbId = 302)
        val rails = buildTvHomeRails(
            state = HomeUiState(
                recentlyWatched = listOf(watchedMovie),
                becauseYouWatched = listOf(
                    CatalogShelf(
                        "because_movie_101",
                        "Wrong dynamic heading",
                        listOf(movieSuggestion),
                        sourceItem = watchedMovie,
                    ),
                    CatalogShelf(
                        "because_tv_202",
                        "Another dynamic heading",
                        listOf(seriesSuggestion),
                        sourceItem = watchedSeries,
                    ),
                ),
            ),
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.RECENTLY_WATCHED, enabled = true, order = 0),
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED, enabled = true, order = 1),
            ),
            customSections = emptyList(),
            homeLayoutOrder = listOf("section:RECENTLY_WATCHED", "section:BECAUSE_YOU_WATCHED"),
        )

        assertEquals(
            listOf("recently_watched", "because_movie_101", "because_tv_202"),
            rails.map { it.key },
        )
        assertEquals(
            listOf(TV_HOME_BECAUSE_YOU_WATCHED_TITLE, TV_HOME_BECAUSE_YOU_WATCHED_TITLE),
            rails.drop(1).map { it.title },
        )
        assertEquals(watchedMovie, rails[1].items.first())
        assertEquals(watchedSeries, rails[2].items.first())
    }

    private fun media(id: String, type: MediaType = MediaType.MOVIE) = MediaItem(
        id = id,
        type = type,
        title = id,
        posterUrl = "/$id.jpg",
    )
}
