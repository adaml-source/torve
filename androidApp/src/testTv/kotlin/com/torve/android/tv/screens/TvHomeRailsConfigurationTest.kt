package com.torve.android.tv.screens

import com.torve.android.tv.components.TvCardStyle
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
    }

    private fun media(id: String, type: MediaType = MediaType.MOVIE) = MediaItem(
        id = id,
        type = type,
        title = id,
        posterUrl = "/$id.jpg",
    )
}
