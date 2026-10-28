package com.torve.android.tv.settings

import org.junit.Assert.*
import org.junit.Test

class TvFocusPreviewPolicyTest {
    @Test fun globalOverridesDisabledPageAndRowWithoutDestroyingChoices() {
        val choices = mapOf("page:home" to false, "row:home:watchlist" to false, "page:movies" to true)
        val global = TvFocusPreviewPolicy(true, choices)
        assertTrue(global.enabled("home", "watchlist"))
        assertTrue(global.enabled("search", "results"))
        val individual = global.copy(global = false)
        assertFalse(individual.enabled("home", "watchlist"))
        assertTrue(individual.enabled("movies"))
        assertEquals(choices, individual.choices)
    }
    @Test fun rowOverridesPageWhileOtherRowsInheritPage() {
        val p = TvFocusPreviewPolicy(choices = mapOf("page:home" to true, "row:home:continue_watching" to false))
        assertFalse(p.enabled("home", "continue_watching"))
        assertTrue(p.enabled("home", "watchlist"))
        assertFalse(p.enabled("movies"))
    }
    @Test fun individualRowCanEnablePreviewOnDisabledPage() {
        val p = TvFocusPreviewPolicy(choices = mapOf("row:home:watchlist" to true))
        assertTrue(p.hasPreview("home")); assertFalse(p.enabled("home", "trending"))
    }
    @Test fun everyLibraryAndProviderRouteUsesCanonicalPageChoice() {
        assertEquals("jellyfin", tvFocusPreviewPage("library_jellyfin"))
        assertEquals("library", tvFocusPreviewPage("library_watchlist"))
        assertEquals("search", tvFocusPreviewPage("tv_search:main"))
        assertEquals("seeAll", tvFocusPreviewPage("see_all:provider_8:movie"))
        assertEquals("people", tvFocusPreviewPage("see_all:person_credits_1:movie"))
        assertEquals("related", tvFocusPreviewPage("details:movie:123"))
    }
}
