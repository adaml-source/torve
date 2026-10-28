package com.torve.android.ui.community

import org.junit.Assert.*
import org.junit.Test

class ArtworkSelectionsTest {
    @Test fun failedTitlelessImageRetainsTheNormalBackdropBeforeTryingPoster() {
        val item = com.torve.domain.model.MediaItem("calendar:show", type = com.torve.domain.model.MediaType.SERIES,
            title = "A scheduled episode", titlelessBackdropUrl = "https://images.test/titleless.jpg",
            backdropUrl = "https://images.test/backdrop.jpg", posterUrl = "https://images.test/poster.jpg")
        val candidates = artworkCandidates(item, landscape = true, preferTitleless = true).toTypedArray()
        val cache = ArtworkSelections()
        assertEquals(item.titlelessBackdropUrl, cache.choose("expanded", *candidates))
        assertEquals(item.backdropUrl, cache.failed("expanded", item.titlelessBackdropUrl, *candidates))
        assertEquals(item.backdropUrl, cache.choose("expanded", *candidates))
        assertEquals(item.posterUrl, cache.failed("expanded", item.backdropUrl, *candidates))
        assertNull(cache.failed("expanded", item.posterUrl, *candidates))
    }
    @Test fun artworkCandidatesKeepPortraitOrderAndExcludeUnusableOrDuplicateUrls() {
        val item = com.torve.domain.model.MediaItem("show", type = com.torve.domain.model.MediaType.SERIES,
            title = "Show", posterUrl = "https://images.test/poster.jpg", backdropUrl = "https://images.test/backdrop.jpg",
            titlelessBackdropUrl = "  https://images.test/backdrop.jpg  ")
        assertEquals(listOf(item.posterUrl, item.backdropUrl), artworkCandidates(item, landscape = false, preferTitleless = true))
        assertEquals(listOf(item.backdropUrl, item.posterUrl), artworkCandidates(item, landscape = true, preferTitleless = true))
        assertEquals(listOf(item.posterUrl), artworkCandidates(item.copy(titlelessBackdropUrl = "", backdropUrl = "invalid"), true, true))
        assertTrue(artworkCandidates(item.copy(isContentPlaceholder = true), true, true).isEmpty())
        assertTrue(artworkCandidates(item.copy(isStubDetail = true), true, true).isEmpty())
    }
    @Test fun failedImagesCanRetryAfterCooldownWithoutReplacingWorkingArtwork() {
        var now = 0L
        val cache = ArtworkSelections(now = { now })
        cache.choose("retry", "poster", null)
        assertNull(cache.failed("retry", "poster", "poster", null))
        now = 29_999; assertNull(cache.choose("retry", "poster", null))
        now = 30_000; assertEquals("poster", cache.choose("retry", "poster", null))
        assertEquals("poster", cache.choose("retry", "new poster", null))
    }
    @Test fun recyclingAndLateEnrichmentKeepTheOriginalArtwork() {
        val cache = ArtworkSelections()
        assertEquals("first", cache.choose("profile:en:movie:1:poster", "first", "backdrop"))
        assertEquals("first", cache.choose("profile:en:movie:1:poster", "enriched", "another"))
    }
    @Test fun accountLanguageAndOrientationAreIndependent() {
        val cache = ArtworkSelections()
        cache.choose("a:en:poster", "English", null)
        assertEquals("German", cache.choose("a:de:poster", "German", null))
        assertEquals("Other profile", cache.choose("b:en:poster", "Other profile", null))
        assertEquals("Backdrop", cache.choose("a:en:landscape", "Backdrop", null))
    }
    @Test fun failedPrimaryUsesFallbackAndNeitherFailureResurrectsOnRecycling() {
        val cache = ArtworkSelections()
        cache.choose("one", "broken", "fallback")
        assertEquals("fallback", cache.failed("one", "broken", "broken", "fallback"))
        assertEquals("fallback", cache.choose("one", "broken", "fallback"))
        assertNull(cache.failed("one", "fallback", "broken", "fallback"))
        assertNull(cache.choose("one", "broken", "fallback"))
        assertEquals("repaired", cache.choose("one", "repaired", "fallback"))
    }
    @Test fun selectionsAreBoundedAndResettable() {
        val cache = ArtworkSelections(2)
        cache.choose("1", "old", null); cache.choose("2", "second", null); cache.choose("3", "third", null)
        assertEquals("new", cache.choose("1", "new", null))
        cache.clear()
        assertEquals("reset", cache.choose("1", "reset", null))
    }
    @Test fun longSynopsisRetainsEveryWordInRemoteSizedParagraphs() {
        val text = (1..600).joinToString(" ") { "Word$it" }
        val paragraphs = readableParagraphs(text)
        assertTrue(paragraphs.size > 4)
        assertTrue(paragraphs.all { it.length <= 450 })
        assertEquals(text, paragraphs.joinToString(" "))
    }
}
