package com.torve.desktop.playback

import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPreResolvedStreamSessionTest {

    @Test
    fun `adult pre-resolved stream starts without imdb id`() {
        val streamUrl = "https://panda.example/u/token/nzb/xxx-release/stream.mkv"

        val session = buildPreResolvedDesktopPlaybackSession(
            streamUrl = streamUrl,
            title = "Example XXX Release",
            sizeBytes = 1_234_567L,
            sourceSurface = "adult",
        )

        assertEquals("adult", session.request.sourceSurface)
        assertEquals(MediaType.MOVIE, session.request.mediaType)
        assertTrue(session.request.mediaId.startsWith("direct:"))
        assertNull(session.request.imdbId)
        assertNull(session.mediaItem.imdbId)
        assertEquals(streamUrl, session.resolvedUrl)
        assertEquals(streamUrl, session.selectedCandidate?.directUrl)
        assertEquals("direct", session.resolvedCandidateId)
        assertNotNull(session.selectedCandidate)
    }
}
