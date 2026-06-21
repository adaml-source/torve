package com.torve.data.addon

import com.torve.data.acceleration.StreamHandoffResponseDto
import com.torve.domain.model.CandidateProvenanceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamHandoffPolicyTest {

    @Test
    fun nonUsenetStreamWithMemoryIdUsesGenericHandoff() {
        val stream = ParsedStream(
            addonName = "Panda",
            quality = "1080p",
            title = "Movie",
            directUrl = "https://provider.example/raw-tokenized-url",
            accelerationMemoryId = "mem-1",
        )

        assertTrue(stream.canUseGenericStreamHandoff())
    }

    @Test
    fun usenetStreamWithMemoryIdKeepsUsenetResolverPath() {
        val stream = ParsedStream(
            addonName = "NzbDAV",
            quality = "1080p",
            title = "Movie",
            accelerationMemoryId = "mem-usenet",
            accelerationProvenanceKind = CandidateProvenanceKind.USENET_NZBDAV,
        )

        assertFalse(stream.canUseGenericStreamHandoff())
    }

    @Test
    fun missingMemoryIdPreservesLegacyFallbackEligibility() {
        val stream = ParsedStream(
            addonName = "Torrentio",
            quality = "1080p",
            title = "Movie",
            infoHash = "0123456789abcdef0123456789abcdef01234567",
        )

        assertFalse(stream.canUseGenericStreamHandoff())
        assertTrue(stream.canUseLegacyDirectFallback())
    }

    @Test
    fun memoryBackedStreamIsNotLegacyFallbackEligibleEvenWhenItHasDirectUrl() {
        val stream = ParsedStream(
            addonName = "Panda",
            quality = "1080p",
            title = "Movie",
            directUrl = "https://provider.example/raw-tokenized-url",
            accelerationMemoryId = "mem-1",
        )

        assertTrue(stream.canUseGenericStreamHandoff())
        assertFalse(stream.canUseLegacyDirectFallback())
    }

    @Test
    fun rawDirectUrlIsNotPersistedWhenGenericHandoffIsAvailable() {
        val stream = ParsedStream(
            addonName = "Panda",
            quality = "1080p",
            title = "Movie",
            directUrl = "https://provider.example/raw-tokenized-url",
            accelerationMemoryId = "mem-1",
        )

        assertNull(stream.directUrlForResolveMemory())
    }

    @Test
    fun returnedHandoffUrlIsMarkedTemporary() {
        val resolved = StreamHandoffResponseDto(
            url = "https://api.torve.app/handoff/stream-1",
            isDirect = false,
            supportsRange = true,
            streamId = "stream-1",
            expiresInSeconds = 300,
        ).toResolvedStream()

        assertTrue(resolved.isTemporary)
        assertFalse(resolved.isDirect)
        assertTrue(resolved.supportsRange)
        assertEquals("stream-1", resolved.streamId)
        assertEquals(300, resolved.expiresInSeconds)
    }
}
