package com.torve.domain.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSegmentDetectorTest {
    @Test
    fun localizedChaptersKeepRecapSeparateFromIntroAndPreview() {
        val evidence = EmbeddedChapterSegmentDetector.detect(
            listOf(
                MediaChapter("Was bisher geschah", 0, 35_000),
                MediaChapter("Vorspann", 72_000, 94_000),
                MediaChapter("Vorschau", 1_250_000, 1_270_000),
            ),
            1_300_000,
        )
        assertEquals(3, evidence.size)
    }

    @Test
    fun subtitleStopAloneDoesNotCreateCredits() {
        assertTrue(SubtitleSegmentDetector.detect(listOf(SubtitleCueObservation(0, 1_000, "Hello")), 1_000_000).isEmpty())
    }

    @Test
    fun visualCreditTextOverDialogueIsProtectedAsOverlay() {
        val result = VisualSegmentDetector.detect(
            listOf(VisualSegmentObservation(900_000, 960_000, creditTextDensity = 0.9, normalSceneMotionScore = 0.8, dialogueActive = true)),
            1_000_000,
        )
        assertTrue(result.evidence.isEmpty())
        assertEquals(1, result.creditsOverContent.size)
    }

    @Test
    fun repeatedAudioRequiresMultipleEpisodesAndStrongSimilarity() {
        assertTrue(AudioFingerprintSegmentDetector.detect(listOf(AudioMatchObservation(10_000, 40_000, 0.95, 1)), 1_000_000).isEmpty())
        assertTrue(AudioFingerprintSegmentDetector.detect(listOf(AudioMatchObservation(10_000, 40_000, 0.5, 5)), 1_000_000).isEmpty())
        assertEquals(1, AudioFingerprintSegmentDetector.detect(listOf(AudioMatchObservation(10_000, 40_000, 0.93, 4)), 1_000_000).size)
    }

    @Test
    fun repeatedCorrectionsUseMedianAndRejectOutlier() {
        val consensus = SegmentObservationConsensus().validate(
            listOf(
                SegmentBoundaryObservation(64_100, 85_800, "skip", 1),
                SegmentBoundaryObservation(64_300, 85_700, "skip", 2),
                SegmentBoundaryObservation(64_200, 85_900, "skip", 3),
                SegmentBoundaryObservation(300_000, 400_000, "skip", 4),
            ),
        )
        assertEquals(64_200, consensus?.startMs)
        assertEquals(85_800, consensus?.endMs)
        assertEquals(3, consensus?.evidenceCount)
    }
}
