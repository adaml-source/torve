package com.torve.presentation.home

import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueWatchingArtworkMergeTest {
    @Test fun newerProgressKeepsExistingArtwork() {
        val local = WatchProgress("42", MediaType.MOVIE, "Movie", "poster", "backdrop", 1000, 10000, updatedAt = 10)
        val overlay = local.copy(posterUrl = "", backdropUrl = null, positionMs = 2000, updatedAt = 20)
        val merged = listOf(local, overlay).newestWithArtwork()!!
        assertEquals("poster", merged.posterUrl)
        assertEquals("backdrop", merged.backdropUrl)
        assertEquals(2000L, merged.positionMs)
        assertEquals(20L, merged.updatedAt)
    }
}
