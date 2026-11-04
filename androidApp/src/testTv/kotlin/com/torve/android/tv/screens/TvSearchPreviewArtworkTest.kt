package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvSearchPreviewArtworkTest {
    private val item = MediaItem("tmdb:42", tmdbId = 42, title = "Original title", type = MediaType.MOVIE)

    @Test fun dedicatedLogoLookupFillsArtworkMissingFromPresentation() = runTest {
        var lookups = 0
        val result = loadTvSearchPreviewArtwork(item,
            presentation = { type, id -> assertEquals("movie", type); assertEquals(42, id); item.copy(overview = "Synopsis", runtime = 100) },
            logo = { _, _ -> lookups++; "https://images.example/title.png" })
        assertEquals("https://images.example/title.png", result.logoUrl)
        assertEquals("Synopsis", result.overview)
        assertEquals(100, result.runtime)
        assertEquals(item.id, result.id)
        assertEquals(item.title, result.title)
        assertEquals(1, lookups)
    }

    @Test fun failedPresentationDoesNotDiscardAvailableTitleArtwork() = runTest {
        val result = loadTvSearchPreviewArtwork(item, { _, _ -> error("offline") }, { _, _ -> "logo" })
        assertEquals("logo", result.logoUrl)
    }

    @Test fun existingArtworkIsPreservedWithoutAnotherLogoRequest() = runTest {
        val result = loadTvSearchPreviewArtwork(item.copy(logoUrl = "existing", overview = "Existing synopsis"),
            { _, _ -> item.copy(logoUrl = "replacement", overview = "Replacement") },
            { _, _ -> fail("Existing artwork must not trigger logo lookup"); null })
        assertEquals("existing", result.logoUrl)
        assertEquals("Existing synopsis", result.overview)
    }

    @Test fun missingArtworkKeepsTheTitleForTheTextFallback() = runTest {
        val result = loadTvSearchPreviewArtwork(item, { _, _ -> item }, { _, _ -> null })
        assertNull(result.logoUrl)
        assertEquals(item.title, result.title)
    }

    @Test fun identifierEmbeddedInResultIdCanResolveArtwork() = runTest {
        val result = loadTvSearchPreviewArtwork(item.copy(tmdbId = null), { _, id -> assertEquals(42, id); item }, { _, _ -> "logo" })
        assertEquals("logo", result.logoUrl)
    }

    @Test fun cancellingTheSelectedTitleStopsItsArtworkWork() = runTest {
        try {
            loadTvSearchPreviewArtwork(item, { _, _ -> throw CancellationException("moved focus") }, { _, _ -> null })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
