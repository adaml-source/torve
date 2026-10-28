package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BecauseYouWatchedSectionMigrationTest {
    @Test
    fun legacyCombinedConfigBecomesIndependentMovieAndTvConfigs() {
        val migrated = migrateLegacyBecauseYouWatchedConfigs(
            listOf(
                HomeSectionConfig(
                    section = HomeSection.BECAUSE_YOU_WATCHED,
                    enabled = true,
                    order = 7,
                    customTitle = "Because You Watched",
                    presetId = "cinema",
                ),
            ),
        )

        assertEquals(
            listOf(HomeSection.BECAUSE_YOU_WATCHED_MOVIES, HomeSection.BECAUSE_YOU_WATCHED_TV),
            migrated.map { it.section },
        )
        assertTrue(migrated.all { it.enabled })
        assertEquals(listOf(7, 8), migrated.map { it.order })
        assertTrue(migrated.all { it.presetId == "cinema" })
        assertTrue(migrated.all { it.customTitle == null })
    }

    @Test
    fun explicitSplitConfigWinsOverStaleLegacyConfig() {
        val migrated = migrateLegacyBecauseYouWatchedConfigs(
            listOf(
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED, enabled = true, order = 4),
                HomeSectionConfig(HomeSection.BECAUSE_YOU_WATCHED_MOVIES, enabled = false, order = 9),
            ),
        )

        val movies = migrated.single { it.section == HomeSection.BECAUSE_YOU_WATCHED_MOVIES }
        val tv = migrated.single { it.section == HomeSection.BECAUSE_YOU_WATCHED_TV }
        assertFalse(movies.enabled)
        assertEquals(9, movies.order)
        assertTrue(tv.enabled)
        assertEquals(5, tv.order)
    }

    @Test
    fun legacyLayoutKeyExpandsInPlaceWithoutDuplicates() {
        assertEquals(
            listOf(
                "section:RECENTLY_WATCHED",
                "section:BECAUSE_YOU_WATCHED_MOVIES",
                "section:BECAUSE_YOU_WATCHED_TV",
                "section:TOP_RATED",
            ),
            migrateLegacyBecauseYouWatchedLayoutOrder(
                listOf(
                    "section:RECENTLY_WATCHED",
                    "section:BECAUSE_YOU_WATCHED",
                    "section:BECAUSE_YOU_WATCHED_TV",
                    "section:TOP_RATED",
                ),
            ),
        )
    }

    @Test
    fun settingsExposeOnlyTheTwoNamedReplacementSections() {
        assertFalse(HomeSection.BECAUSE_YOU_WATCHED.configurable)
        assertTrue(HomeSection.BECAUSE_YOU_WATCHED_MOVIES in configurableHomeSections)
        assertTrue(HomeSection.BECAUSE_YOU_WATCHED_TV in configurableHomeSections)
        assertEquals("Because You Watched (Movies)", HomeSection.BECAUSE_YOU_WATCHED_MOVIES.defaultTitle)
        assertEquals("Because You Watched (TV Shows)", HomeSection.BECAUSE_YOU_WATCHED_TV.defaultTitle)
        assertNull(HomeSection.BECAUSE_YOU_WATCHED.shelfId)
    }
}
