package com.torve.presentation.home

import com.torve.domain.model.HomeSection
import com.torve.domain.model.HomeSectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLayoutOrderingTest {
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
