package com.torve.data.addon

import com.torve.domain.model.SourceLanguageMatchMode
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.model.UnknownSourceMetadataPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAutoSelectionPolicyTest {
    private val oneHour = 60L * 60L * 1_000L
    private val twoGiB = 2L * 1024L * 1024L * 1024L

    @Test
    fun durationNormalizedFloorRequiresOneGiBForThirtyMinutes() {
        val required = StreamAutoSelectionPolicy.minimumSourceBytes(twoGiB, oneHour / 2)
        assertEquals(1L * 1024L * 1024L * 1024L, required)
    }

    @Test
    fun automaticSelectionRejectsUndersizedEpisodeSource() {
        val decision = StreamAutoSelectionPolicy.evaluate(
            stream = stream(size = "900 MB", languages = listOf("EN")),
            preferences = preferences(),
            context = StreamSelectionContext(durationMs = oneHour / 2, activeAudioLanguage = "en"),
        )

        assertFalse(decision.eligible)
        assertEquals(StreamRejectionReason.BELOW_MINIMUM_SIZE_PER_HOUR, decision.rejectionReason)
    }

    @Test
    fun currentAudioLanguageRanksAheadOfSecondaryPreference() {
        val prefs = preferences().copy(
            preferredAudioLanguages = listOf("de", "en"),
            sourceLanguageMatchMode = SourceLanguageMatchMode.PREFER,
        )
        val english = StreamAutoSelectionPolicy.evaluate(
            stream = stream(size = "2.5 GB", languages = listOf("EN")),
            preferences = prefs,
            context = StreamSelectionContext(durationMs = oneHour, activeAudioLanguage = "English"),
        )
        val german = StreamAutoSelectionPolicy.evaluate(
            stream = stream(size = "2.5 GB", languages = listOf("DE")),
            preferences = prefs,
            context = StreamSelectionContext(durationMs = oneHour, activeAudioLanguage = "English"),
        )

        assertTrue(english.eligible)
        assertTrue(german.eligible)
        assertTrue(english.scoreAdjustment > german.scoreAdjustment)
    }

    @Test
    fun strictLanguageDoesNotPreloadRandomSource() {
        val decision = StreamAutoSelectionPolicy.evaluate(
            stream = stream(size = "2.5 GB", languages = listOf("FR")),
            preferences = preferences().copy(sourceLanguageMatchMode = SourceLanguageMatchMode.REQUIRE),
            context = StreamSelectionContext(durationMs = oneHour, activeAudioLanguage = "en"),
        )

        assertFalse(decision.eligible)
        assertEquals(StreamRejectionReason.LANGUAGE_MISMATCH, decision.rejectionReason)
    }

    @Test
    fun unknownSizeCanBeStrictlyRejectedForAutomaticPreparation() {
        val decision = StreamAutoSelectionPolicy.evaluate(
            stream = stream(size = null, languages = listOf("EN")),
            preferences = preferences().copy(unknownSourceSizePolicy = UnknownSourceMetadataPolicy.REJECT),
            context = StreamSelectionContext(durationMs = oneHour, activeAudioLanguage = "en"),
        )

        assertFalse(decision.eligible)
        assertEquals(StreamRejectionReason.UNKNOWN_SIZE, decision.rejectionReason)
    }

    private fun preferences() = StreamPreferences(
        minQuality = StreamQuality.FHD_1080P,
        maxQuality = StreamQuality.REMUX_4K,
        cachedOnly = false,
        minSourceSizePerHourBytes = twoGiB,
        preferredAudioLanguages = listOf("en"),
    )

    private fun stream(size: String?, languages: List<String>) = ParsedStream(
        addonName = "Test",
        quality = "1080p",
        title = "Episode",
        directUrl = "https://example.test/episode.mkv",
        size = size,
        languages = languages,
        isCached = true,
    )
}
