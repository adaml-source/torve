package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeSectionPresetUpdateTest {

    @Test
    fun updateSectionPresetId_updatesOnlyTargetSection() {
        val initial = listOf(
            HomeSectionConfig(section = HomeSection.TRENDING_MOVIES, enabled = true, order = 0, presetId = null),
            HomeSectionConfig(section = HomeSection.TRENDING_TV, enabled = true, order = 1, presetId = "alt"),
            HomeSectionConfig(section = HomeSection.TOP_RATED, enabled = true, order = 2, presetId = null),
        )

        val updated = updateSectionPresetId(
            configs = initial,
            section = HomeSection.TOP_RATED,
            presetId = "cinema",
        )

        assertNull(updated.first { it.section == HomeSection.TRENDING_MOVIES }.presetId)
        assertEquals("alt", updated.first { it.section == HomeSection.TRENDING_TV }.presetId)
        assertEquals("cinema", updated.first { it.section == HomeSection.TOP_RATED }.presetId)
    }

    @Test
    fun updateSectionPresetId_canRevertToDefaultInheritance() {
        val initial = listOf(
            HomeSectionConfig(section = HomeSection.TRENDING_MOVIES, enabled = true, order = 0, presetId = "cinema"),
        )

        val updated = updateSectionPresetId(
            configs = initial,
            section = HomeSection.TRENDING_MOVIES,
            presetId = null,
        )

        assertNull(updated.single().presetId)
    }
}

