package com.torve.data.integrations

import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeerrMediaLifecycleServiceTest {
    @Test
    fun `missing media info is requestable`() {
        val status = deriveSeerrLifecycleStatus(42, MediaType.MOVIE, null)
        assertEquals(MediaLifecycleState.NOT_REQUESTED, status.state)
        assertTrue(status.canRequest)
        assertFalse(status.isInProgress)
    }

    @Test
    fun `pending approval remains distinct from processing`() {
        val status = deriveSeerrLifecycleStatus(
            42,
            MediaType.MOVIE,
            SeerrMediaInfoDto(
                tmdbId = 42,
                status = 2,
                requests = listOf(SeerrRequestDto(id = 9, status = 1)),
            ),
        )
        assertEquals(MediaLifecycleState.PENDING_APPROVAL, status.state)
        assertEquals(9, status.requestId)
        assertTrue(status.isInProgress)
    }

    @Test
    fun `available media wins over stale request status`() {
        val status = deriveSeerrLifecycleStatus(
            42,
            MediaType.SERIES,
            SeerrMediaInfoDto(
                tmdbId = 42,
                status = 5,
                requests = listOf(SeerrRequestDto(id = 10, status = 1)),
            ),
        )
        assertEquals(MediaLifecycleState.AVAILABLE, status.state)
        assertFalse(status.canRequest)
    }

    @Test
    fun `latest request controls state`() {
        val status = deriveSeerrLifecycleStatus(
            42,
            MediaType.MOVIE,
            SeerrMediaInfoDto(
                tmdbId = 42,
                requests = listOf(
                    SeerrRequestDto(id = 2, status = 3),
                    SeerrRequestDto(id = 7, status = 2, is4k = true),
                ),
            ),
            is4k = true,
        )
        assertEquals(MediaLifecycleState.APPROVED, status.state)
        assertEquals(7, status.requestId)
        assertTrue(status.is4k)
    }

    @Test
    fun `available old season does not block requesting a different season`() {
        val status = deriveSeerrLifecycleStatus(
            tmdbId = 42,
            mediaType = MediaType.SERIES,
            media = SeerrMediaInfoDto(
                tmdbId = 42,
                status = 5,
                requests = listOf(
                    SeerrRequestDto(
                        id = 10,
                        status = 2,
                        seasons = listOf(SeerrRequestSeasonDto(seasonNumber = 1)),
                    ),
                ),
            ),
            seasons = listOf(2),
        )

        assertEquals(MediaLifecycleState.NOT_REQUESTED, status.state)
        assertTrue(status.canRequest)
    }

    @Test
    fun `4k request uses independent 4k media status`() {
        val status = deriveSeerrLifecycleStatus(
            tmdbId = 42,
            mediaType = MediaType.MOVIE,
            media = SeerrMediaInfoDto(
                tmdbId = 42,
                status = 5,
                status4k = 3,
                requests = listOf(SeerrRequestDto(id = 8, status = 2, is4k = true)),
            ),
            is4k = true,
        )

        assertEquals(MediaLifecycleState.PROCESSING, status.state)
        assertTrue(status.is4k)
    }

    @Test
    fun `server URL requires explicit http transport`() {
        assertEquals(
            "https://seerr.example.test",
            SeerrMediaLifecycleService.normalizeBaseUrl(" https://seerr.example.test/ "),
        )
        assertEquals(null, SeerrMediaLifecycleService.normalizeBaseUrl("seerr.example.test"))
    }
}
