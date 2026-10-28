package com.torve.data.addon

import com.torve.presentation.apple.ApplePlaybackSynopsis
import com.torve.presentation.apple.ApplePlaybackSynopsisPolicy
import kotlin.test.*

class CatalogueNoticeTest {
    @Test fun configurationResponseIsANoticeRatherThanAMovie() {
        val item = StremioMeta(id="ai-error", type="movie", name="AI Error",
            description="The selected Gemini Model is invalid or not found. Please try a different model in the settings.").toMediaItem()
        assertNotNull(item.catalogueNotice)
        assertTrue(item.isContentPlaceholder)
        assertTrue(item.catalogueNotice!!.contains("Gemini"))
    }
    @Test fun realFilmNamedErrorIsNotMistakenForAProviderFailure() {
        val item = StremioMeta(id="tt1234567", type="movie", name="Error", description="A model finds a mysterious key.").toMediaItem()
        assertNull(item.catalogueNotice)
        assertFalse(item.isContentPlaceholder)
    }
    @Test fun fullerPlotPreservesParagraphsAndShorterOrUnavailableDataCannotReplaceIt() {
        val base = ApplePlaybackSynopsis("Film", "Short summary.")
        val long = "A more detailed first paragraph.\n\nThe complete final paragraph."
        val full = ApplePlaybackSynopsisPolicy.preferFull(base, long, "OMDb")
        assertEquals(long, full.overview); assertEquals("OMDb", full.source)
        assertEquals(full, ApplePlaybackSynopsisPolicy.preferFull(full, "N/A", "TMDB"))
        assertEquals(full, ApplePlaybackSynopsisPolicy.preferFull(full, "Short.", "TMDB"))
    }
}
