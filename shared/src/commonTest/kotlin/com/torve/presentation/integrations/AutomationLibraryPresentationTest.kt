package com.torve.presentation.integrations

import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationLibraryPresentationTest {
    @Test
    fun `partially monitored series offers explicit monitor and search action`() {
        val item = AutomationLibraryItem(
            id = 12,
            kind = AutomationMediaKind.SERIES,
            title = "Example Show",
            monitored = true,
            seasonCount = 12,
            monitoredSeasonCount = 2,
            episodeCount = 262,
            episodeFileCount = 0,
        )

        assertTrue(item.requiresSeasonMonitoring())
        assertEquals("Monitor seasons + search", item.primaryActionLabel())
        assertTrue("2/12 seasons monitored" in item.statusParts())
        assertTrue("0/262 episodes downloaded" in item.statusParts())
    }

    @Test
    fun `fully monitored series searches missing episodes without opening release rss`() {
        val item = AutomationLibraryItem(
            id = 12,
            kind = AutomationMediaKind.SERIES,
            title = "Example Show",
            monitored = true,
            seasonCount = 12,
            monitoredSeasonCount = 12,
        )

        assertFalse(item.requiresSeasonMonitoring())
        assertEquals("Search missing", item.primaryActionLabel())
    }

    @Test
    fun `movie keeps interactive release action`() {
        val item = AutomationLibraryItem(
            id = 7,
            kind = AutomationMediaKind.MOVIE,
            title = "Example Movie",
        )

        assertEquals("Find releases", item.primaryActionLabel())
    }
}
