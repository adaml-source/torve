package com.torve.presentation.home

import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLayoutOrderingTest {
    @Test
    fun movieAndTvCatalogueSplitKeepsTheOldPositionAndAnyExplicitTypedOrder() {
        val movies = "provider-popular::type=movie"
        val shows = "provider-popular::type=series"
        assertEquals(listOf("section:HERO", "addon:$movies", "addon:$shows", "custom:later"),
            expandLegacyAddonLayoutKeys(listOf("section:HERO", "addon:provider-popular", "custom:later"),
                mapOf("provider-popular" to listOf(movies, shows))))
        assertEquals(listOf("addon:$shows", "addon:$movies"),
            expandLegacyAddonLayoutKeys(listOf("addon:$shows", "addon:provider-popular"),
                mapOf("provider-popular" to listOf(movies, shows))))
    }

    @Test
    fun partiallySavedLayoutCanAddTheNegativeOrderSearchSectionWithoutCrashing() {
        val saved = listOf("addon:netflix", "section:HERO")
        val configs = listOf(
            HomeSectionConfig(HomeSection.SEARCH_BAR, enabled = false, order = -1),
            HomeSectionConfig(HomeSection.HERO, enabled = true, order = 0),
            HomeSectionConfig(HomeSection.POPULAR_MOVIES, enabled = true, order = 7),
        )
        assertEquals(listOf("section:SEARCH_BAR", "addon:netflix", "section:HERO", "section:POPULAR_MOVIES"),
            completeHomeLayoutOrder(saved, configs))
        assertEquals(emptyList(), completeHomeLayoutOrder(emptyList(), configs))
    }

    @Test
    fun reorderingBuiltInsPreservesCustomAndAddonEntries() {
        val configs = listOf(
            HomeSectionConfig(HomeSection.TRENDING_TV, enabled = true, order = 0),
            HomeSectionConfig(HomeSection.TRENDING_MOVIES, enabled = true, order = 1),
        )

        val merged = mergeBuiltInHomeLayoutOrder(
            existingOrder = listOf(
                "section:TRENDING_MOVIES",
                "custom:winter",
                "section:TRENDING_TV",
                "addon:cinemeta-popular",
            ),
            configs = configs,
        )

        assertEquals(
            listOf(
                "section:TRENDING_TV",
                "custom:winter",
                "section:TRENDING_MOVIES",
                "addon:cinemeta-popular",
            ),
            merged,
        )
    }
}
