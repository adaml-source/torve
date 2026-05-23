package com.torve.desktop.playback

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHandoffExpiryRecoveryTest {

    @Test
    fun `temporary memory handoff is refreshable`() {
        val session = handoffSession()

        assertTrue(session.hasRefreshableTemporaryHandoff())
        assertTrue(
            shouldAttemptDesktopHandoffRefresh(
                session = session,
                code = "stream_expired",
                message = "stream_expired",
                recoverable = true,
                attempts = 0,
            ),
        )
    }

    @Test
    fun `generic player error refreshes only bounded temporary handoff`() {
        assertTrue(
            shouldAttemptDesktopHandoffRefresh(
                session = handoffSession(),
                code = "VLC_PLAYBACK_ERROR",
                message = "VLC encountered a playback error.",
                recoverable = true,
                attempts = 0,
            ),
        )
        assertFalse(
            shouldAttemptDesktopHandoffRefresh(
                session = handoffSession(),
                code = "VLC_PLAYBACK_ERROR",
                message = "VLC encountered a playback error.",
                recoverable = true,
                attempts = DESKTOP_HANDOFF_REFRESH_MAX_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `direct or non memory sessions do not trigger handoff refresh`() {
        assertFalse(
            shouldAttemptDesktopHandoffRefresh(
                session = handoffSession(memoryId = null),
                code = "stream_expired",
                message = "stream_expired",
                recoverable = true,
                attempts = 0,
            ),
        )
        assertFalse(
            shouldAttemptDesktopHandoffRefresh(
                session = handoffSession(isTemporary = false),
                code = "stream_expired",
                message = "stream_expired",
                recoverable = true,
                attempts = 0,
            ),
        )
    }

    @Test
    fun `known expired handoff codes are recognized`() {
        assertTrue(isDesktopExpiredHandoffFailure("stream_expired", "expired"))
        assertTrue(isDesktopExpiredHandoffFailure("HTTP_410", "410 gone"))
        assertTrue(isDesktopExpiredHandoffFailure("invalid_handoff", "tampered"))
        assertFalse(isDesktopExpiredHandoffFailure("NO_DEBRID_ACCOUNT", "connect provider"))
    }

    private fun handoffSession(
        memoryId: String? = "mem_123",
        isTemporary: Boolean = true,
    ): DesktopPlaybackSession {
        val request = DesktopPlaybackRequest(
            mediaId = "tmdb:movie:1",
            mediaType = MediaType.MOVIE,
            title = "Movie",
            sourceSurface = "detail",
        )
        val candidate = DesktopPlaybackSourceCandidate(
            candidateId = "candidate-1",
            addonName = "Panda",
            title = "Movie 1080p",
            quality = "1080p",
            score = 100,
            accelerationMemoryId = memoryId,
        )
        return DesktopPlaybackSession(
            request = request,
            mediaItem = MediaItem(id = request.mediaId, title = request.title, type = request.mediaType),
            selectedCandidate = candidate,
            resolvedCandidateId = candidate.candidateId,
            resolvedUrl = "https://api.torve.app/resolver/stream/handoff/test-token",
            resolvedIsTemporary = isTemporary,
            resolvedStreamId = "stream_1",
            resolvedExpiresInSeconds = 300,
        )
    }
}
