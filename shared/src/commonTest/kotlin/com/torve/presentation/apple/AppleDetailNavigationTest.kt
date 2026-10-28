package com.torve.presentation.apple

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleDetailNavigationTest {
    @Test fun upcomingEpisodeOpensItsShowRegardlessOfAirDate() {
        for (date in listOf("2026-10-11T13:00:00Z", "2099-10-11T13:00:00+00:00")) {
            val item = MediaItem(id = "trakt-calendar:1399:99:1:$date", tmdbId = 1399,
                type = MediaType.SERIES, title = "Upcoming episode", releaseDate = date)
            assertEquals("1399", AppleDetailNavigation.mediaId(item))
            assertEquals(1399, AppleDetailNavigation.catalogId(item.id))
        }
    }

    @Test fun explicitShowIdentityWinsOverOpaqueShelfOrResumeIdentity() {
        for (key in listOf("trakt-calendar:show:2:20:2099-10-11T13:00:00Z", "provider:resume:20")) {
            assertEquals("1418", AppleDetailNavigation.mediaId(MediaItem(id = key, tmdbId = 1418,
                type = MediaType.SERIES, title = "Resume")))
        }
    }

    @Test fun calendarWithOnlyShowTitleDoesNotUseEpisodeOrTimezoneAsShow() {
        assertNull(AppleDetailNavigation.catalogId("trakt-calendar:Unmapped show:2:20:2099-10-11T13:00:00+00:00"))
        assertNull(AppleDetailNavigation.catalogId("trakt-calendar:Unmapped show:2:20:"))
        assertNull(AppleDetailNavigation.catalogId("trakt-calendar:0:2:20:2099-10-11T13:00:00Z"))
    }

    @Test fun existingCatalogAndResumeIdsStillWorkWithoutAcceptingInvalidIds() {
        assertEquals(1418, AppleDetailNavigation.catalogId("1418"))
        assertEquals(1418, AppleDetailNavigation.catalogId(" tmdb:1418 "))
        assertEquals(1418, AppleDetailNavigation.catalogId("tv_1418"))
        for (id in listOf("", "missing", "0", "tt0944947:2:20", "999999999999999")) {
            assertNull(AppleDetailNavigation.catalogId(id))
        }
    }
}
