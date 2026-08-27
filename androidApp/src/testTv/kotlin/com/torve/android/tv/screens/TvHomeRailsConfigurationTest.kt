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
    fun becauseYouWatchedMovieRailContainsOnlyWatchedSourcePosters() {
        val watchedMovieA = media("watched-movie-a").copy(tmdbId = 101)
        val watchedMovieB = media("watched-movie-b").copy(tmdbId = 102)
        val rails = buildTvHomeRails(
            state = HomeUiState(
                becauseYouWatched = listOf(
                    CatalogShelf(
                        id = BECAUSE_YOU_WATCHED_MOVIES_ID,
                        title = BECAUSE_YOU_WATCHED_MOVIES_TITLE,
                        items = listOf(watchedMovieA, watchedMovieB),
                    ),
                ),
            ),
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_MOVIES, enabled = true, order = 0),
            ),
            customSections = emptyList(),
            homeLayoutOrder = listOf("section:BECAUSE_YOU_WATCHED_MOVIES"),
        )

        val rail = rails.single()
        assertEquals(BECAUSE_YOU_WATCHED_MOVIES_ID, rail.key)
        assertEquals(BECAUSE_YOU_WATCHED_MOVIES_TITLE, rail.title)
        assertEquals(listOf(watchedMovieA, watchedMovieB), rail.items)
        assertFalse(rail.showSeeAllCard)
        assertTrue(rail.allowCrossRailDuplicates)
        assertEquals("more_like_movie_101", watchedMovieA.tvMoreLikeRailKey())
    }

    @Test
    fun becauseYouWatchedIsExactlyOneMoviesAndOneTvShowsRail() {
        val watchedMovie = media("watched-movie").copy(tmdbId = 101)
        val watchedSeries = media("watched-series", MediaType.SERIES).copy(tmdbId = 202)
        val rails = buildTvHomeRails(
            state = HomeUiState(
                recentlyWatched = listOf(watchedMovie),
                becauseYouWatched = listOf(
                    CatalogShelf(
                        BECAUSE_YOU_WATCHED_MOVIES_ID,
                        BECAUSE_YOU_WATCHED_MOVIES_TITLE,
                        listOf(watchedMovie),
                    ),
                    CatalogShelf(
                        BECAUSE_YOU_WATCHED_TV_ID,
                        BECAUSE_YOU_WATCHED_TV_TITLE,
                        listOf(watchedSeries),
                    ),
                ),
            ),
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.RECENTLY_WATCHED, enabled = true, order = 0),
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_MOVIES, enabled = true, order = 1),
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_TV, enabled = true, order = 2),
            ),
            customSections = emptyList(),
            homeLayoutOrder = listOf(
                "section:RECENTLY_WATCHED",
                "section:BECAUSE_YOU_WATCHED_MOVIES",
                "section:BECAUSE_YOU_WATCHED_TV",
            ),
        )

        assertEquals(
            listOf("recently_watched", BECAUSE_YOU_WATCHED_MOVIES_ID, BECAUSE_YOU_WATCHED_TV_ID),
            rails.map { it.key },
        )
        assertEquals(
            listOf(BECAUSE_YOU_WATCHED_MOVIES_TITLE, BECAUSE_YOU_WATCHED_TV_TITLE),
            rails.drop(1).map { it.title },
        )
        assertEquals(watchedMovie, rails[1].items.first())
        assertEquals(watchedSeries, rails[2].items.first())
    }

    @Test
    fun becauseYouWatchedMovieAndTvRailsCanBeEnabledIndependently() {
        val watchedMovie = media("watched-movie").copy(tmdbId = 101)
        val watchedSeries = media("watched-series", MediaType.SERIES).copy(tmdbId = 202)
        val state = HomeUiState(
            becauseYouWatched = listOf(
                CatalogShelf(
                    BECAUSE_YOU_WATCHED_MOVIES_ID,
                    BECAUSE_YOU_WATCHED_MOVIES_TITLE,
                    listOf(watchedMovie),
                ),
                CatalogShelf(
                    BECAUSE_YOU_WATCHED_TV_ID,
                    BECAUSE_YOU_WATCHED_TV_TITLE,
                    listOf(watchedSeries),
                ),
            ),
        )

        val movieOnly = buildTvHomeRails(
            state = state,
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_MOVIES, enabled = true, order = 0),
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_TV, enabled = false, order = 1),
            ),
            customSections = emptyList(),
            homeLayoutOrder = emptyList(),
        )
        val tvOnly = buildTvHomeRails(
            state = state,
            sectionConfigs = listOf(
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_MOVIES, enabled = false, order = 0),
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_TV, enabled = true, order = 1),
            ),
            customSections = emptyList(),
            homeLayoutOrder = emptyList(),
        )

        assertEquals(listOf(BECAUSE_YOU_WATCHED_MOVIES_ID), movieOnly.map { it.key })
        assertEquals(listOf(BECAUSE_YOU_WATCHED_TV_ID), tvOnly.map { it.key })
    }

    private fun media(id: String, type: MediaType = MediaType.MOVIE) = MediaItem(
        id = id,
        type = type,
        title = id,
        posterUrl = "/$id.jpg",
    )
}

private const val BECAUSE_YOU_WATCHED_MOVIES_ID = "because_you_watched_movies"
private const val BECAUSE_YOU_WATCHED_MOVIES_TITLE = "Because You Watched (Movies)"
private const val BECAUSE_YOU_WATCHED_TV_ID = "because_you_watched_tv"
private const val BECAUSE_YOU_WATCHED_TV_TITLE = "Because You Watched (TV Shows)"
