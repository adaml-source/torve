package com.torve.presentation.apple

import com.torve.domain.model.*
import com.torve.presentation.home.*
import kotlin.test.*

class AppleTvHomeLayoutTest {
    @Test fun externalCataloguesFilterByTypeWithoutLosingProviderIdentity() {
        val movie = MediaItem(id = "tt1234567", title = "Movie", type = MediaType.MOVIE)
        val show = MediaItem(id = "tmdb:55", title = "Show", type = MediaType.SERIES)
        val shelves = listOf(CatalogShelf("provider-netflix", "Netflix", listOf(movie, show, movie)))
        val movies = AppleTvHomeLayout.catalogues(shelves, MediaType.MOVIE).single()
        val shows = AppleTvHomeLayout.catalogues(shelves, MediaType.SERIES).single()
        assertEquals("provider-netflix", movies.id)
        assertEquals(listOf(movie), movies.items)
        assertEquals("Netflix · Movies", movies.title)
        assertEquals("Netflix · TV shows", shows.title)
        assertEquals(listOf(show), shows.items)
    }
    @Test fun externalCatalogueWithNoMatchingTypeDoesNotRenderAnEmptyRail() {
        assertTrue(AppleTvHomeLayout.catalogues(listOf(CatalogShelf("empty", "Empty", emptyList())), MediaType.SERIES).isEmpty())
    }
    private fun config(section: HomeSection, enabled: Boolean = true) =
        HomeSectionConfig(section, enabled, section.defaultOrder)
    private fun project(state: HomeUiState = HomeUiState(), configs: List<HomeSectionConfig>,
                        custom: List<CustomSection> = emptyList(), order: List<String> = emptyList(),
                        special: Map<HomeSection, List<AppleTvRow>> = emptyMap()) =
        AppleTvHomeLayout.shelves(AppleHomeLayoutPolicy.rows(state, configs, custom, order), special) {
            AppleTvRow(it.id, it.title)
        }

    @Test fun allEnabledRowsSurviveEmptyDataAndSavedCustomOrder() {
        val configs = configurableHomeSections.map { config(it) }
        val result = project(configs = configs, custom = listOf(CustomSection("last", "My row")),
            order = listOf("custom:last", "section:HIDDEN_GEMS", "section:UP_NEXT"))
        assertEquals(configs.size + 1, result.size)
        assertEquals(listOf("custom:last", "section:HIDDEN_GEMS", "section:UP_NEXT"), result.take(3).map { it.id })
        assertTrue(result.all { it.emptyMessage.isNotBlank() })
        assertEquals(configurableHomeSections.map { it.name }.toSet(), result.map { it.section }.filter { it.isNotEmpty() }.toSet())
    }

    @Test fun populatedDisabledSectionCannotReappearAndUpNextHasNoThreeItemCap() {
        val next = (1..8).map { AppleTvRow("next$it", "Episode $it") }
        val result = project(configs = listOf(config(HomeSection.UP_NEXT), config(HomeSection.HERO, false)),
            special = mapOf(HomeSection.UP_NEXT to next, HomeSection.HERO to listOf(AppleTvRow("hero", "Hero"))))
        assertEquals(listOf("section:UP_NEXT"), result.map { it.id })
        assertEquals(next, result.single().items)
    }

    @Test fun peopleCatalogAndContinueWatchingRetainTheirDifferentDestinations() {
        val state = HomeUiState(popularActors = listOf(PersonSummary(123, "Actor")),
            shelves = listOf(CatalogShelf("trending-movies", "Trending", listOf(MediaItem(id = "movie", title = "Movie", type = MediaType.MOVIE)))))
        val resume = AppleTvRow("show", "Show", mediaType = "tv", season = 2, episode = 3, progress = 50f)
        val result = project(state, listOf(config(HomeSection.ACTORS), config(HomeSection.TRENDING_MOVIES),
            config(HomeSection.CONTINUE_WATCHING)), special = mapOf(HomeSection.CONTINUE_WATCHING to listOf(resume)))
        assertEquals("person", result.first { it.section == "ACTORS" }.items.single().mediaType)
        assertEquals("123", result.first { it.section == "ACTORS" }.items.single().targetId)
        assertEquals("movie", result.first { it.section == "TRENDING_MOVIES" }.items.single().id)
        assertEquals(resume, result.first { it.section == "CONTINUE_WATCHING" }.items.single())
    }
}
