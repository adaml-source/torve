package com.torve.android.tv.components

import com.torve.data.addon.ParsedStream
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.domain.model.CandidateProvenance
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupConfidenceReasonCode
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TvStreamPresentationTest {
    private val stream = ParsedStream("Panda", "1080p", "Bayern München.1080p.mkv",
        size = "7.2 GB", languages = listOf("EN", "DE"), codec = "HEVC", isCached = true)

    @Test
    fun summaryLeadsWithReadableLanguagesAndPreservesUnicodeFilename() {
        val result = tvStreamPresentation(stream)
        assertEquals("1080p · English + German · 7.2 GB", result.summary)
        assertEquals("Bayern München.1080p.mkv", result.filename)
        assertEquals(TvStreamAvailability.READY, result.availability)
        assertEquals("Cached at your provider", result.reason)
        assertEquals("HEVC", result.technicalDetails)
    }

    @Test
    fun missingMetadataIsExplicitAndDoesNotGuessAnEnglishTrack() {
        val result = tvStreamPresentation(ParsedStream("provider", "Unknown", "English.1080p.mkv"))
        assertEquals("Quality unknown · Language unknown · Size unknown", result.summary)
        assertEquals(TvStreamAvailability.CHECK_ON_PLAY, result.availability)
    }

    @Test
    fun anAddonUsenetResolverUrlDoesNotProveReadiness() {
        val result = tvStreamPresentation(stream.copy(isCached = false, source = "Usenet",
            directUrl = "https://example.invalid/nzb/source"))
        assertEquals(TvStreamAvailability.CHECK_ON_PLAY, result.availability)
        assertNull(result.reason)
    }

    @Test
    fun currentPreparingOrUnavailableStateOverridesStaleCacheSignals() {
        val preparing = UsenetCandidateUiModel(candidateId = "candidate", availabilityState = UsenetAvailability.PREPARING,
            lastStateChangeAt = 100)
        assertEquals(TvStreamAvailability.PREPARING, tvStreamPresentation(stream, usenet = preparing).availability)
        assertEquals(TvStreamAvailability.UNAVAILABLE,
            tvStreamPresentation(stream, usenet = preparing.copy(availabilityState = UsenetAvailability.UNAVAILABLE)).availability)
    }

    @Test
    fun unavailableStartupCandidateCannotBeMadeReadyByADirectUrlOrHistory() {
        val result = tvStreamPresentation(stream.copy(directUrl = "https://example.invalid/play", recentSuccessCount = 3),
            startup(ReadinessState.UNAVAILABLE))
        assertEquals(TvStreamAvailability.UNAVAILABLE, result.availability)
        assertEquals("Try another source.", result.reason)
    }

    @Test
    fun explanationUsesObservedSuccessRatherThanAnUnexplainedScore() {
        val unverified = stream.copy(isCached = false, score = 100)
        assertNull(tvStreamPresentation(unverified).reason)
        val known = startup(ReadinessState.READY_WITH_RESOLVE)
            .copy(confidenceReasons = listOf(StartupConfidenceReasonCode.RECENT_SUCCESS))
        assertEquals("Played successfully before", tvStreamPresentation(unverified, known).reason)
        assertEquals(TvStreamAvailability.CHECK_ON_PLAY, tvStreamPresentation(unverified, known).availability)
    }

    @Test
    fun startupSizeAndUnknownLanguageTagsHaveUsefulFallbacks() {
        val result = tvStreamPresentation(stream.copy(size = null, languages = listOf("und", "EN", "en")),
            startup(ReadinessState.READY_NOW).copy(sizeBytes = 7_200_000_000))
        assertEquals("1080p · English · 7.2 GB", result.summary)
    }

    private fun startup(readiness: ReadinessState) = StartupCandidate(
        streamKey = "candidate", title = "title", qualityLabel = "1080p", addonName = "Panda",
        readinessState = readiness, provenance = CandidateProvenance(CandidateProvenanceKind.STARTUP_FETCH),
    )
}
