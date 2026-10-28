package com.torve.domain.player

import kotlin.test.*

class PlaybackViewingCoverageTest {
    @Test fun alternatingDecoderClockTicksRetainContinuousViewing() {
        val tracker = PlaybackViewingTracker(180_000)
        tracker.observe(0, 180_000, 0, true, true, 1.0)
        for (second in 0L until 165L) {
            tracker.observe(second * 1000 + 776, 180_000, second * 1000 + 500, true, true, 1.0)
            tracker.observe((second + 1) * 1000, 180_000, (second + 1) * 1000, true, true, 1.0)
        }
        assertEquals(165_000, tracker.snapshot().watchedMs)
        assertTrue(tracker.snapshot().complete)
    }
    @Test fun repeatedDecoderTicksDoNotLoseTheNextValidMovement() {
        val tracker = PlaybackViewingTracker(60_000)
        for (second in 0L..52L) {
            tracker.observe(second * 1000, 60_000, second * 1000, true, true, 1.0)
            tracker.observe(second * 1000, 60_000, second * 1000 + 500, true, true, 1.0)
        }
        assertEquals(52_000, tracker.snapshot().watchedMs)
        assertTrue(tracker.snapshot().complete)
    }
    private val minute = 60_000L
    private fun play(tracker: PlaybackViewingTracker, from: Long, to: Long, time: Long = from, rate: Double = 1.0) {
        var p = from
        while (p <= to) {
            tracker.observe(p, minute, time + ((p - from) / rate).toLong(), true, true, rate)
            p += 500
        }
    }

    @Test fun openingOrPreparingAFileDoesNotCount() {
        val tracker = PlaybackViewingTracker(minute)
        tracker.observe(0, minute, 0, true, false, 1.0)
        tracker.observe(59_000, minute, 1_000, false, true, 1.0)
        assertFalse(tracker.hasPlayed)
        assertFalse(tracker.snapshot().complete)
    }
    @Test fun seekToCreditsCannotCompleteTheTitle() {
        val tracker = PlaybackViewingTracker(minute)
        play(tracker, 0, 5_000)
        play(tracker, 55_000, minute, time = 5_500)
        assertEquals(10_000, tracker.snapshot().watchedMs)
        assertFalse(tracker.snapshot().complete)
    }
    @Test fun explicitSeekEvenWithinTimingToleranceStartsANewInterval() {
        val tracker = PlaybackViewingTracker(minute)
        play(tracker, 0, 1_000)
        tracker.discontinuity()
        play(tracker, 1_500, 2_000, time = 1_500)
        assertEquals(1_500, tracker.snapshot().watchedMs)
    }
    @Test fun replayedSectionsAreCountedOnlyOnce() {
        val tracker = PlaybackViewingTracker(minute)
        play(tracker, 0, 30_000)
        tracker.discontinuity()
        play(tracker, 0, 30_000, time = 31_000)
        assertEquals(30_000, tracker.snapshot().watchedMs)
        assertFalse(tracker.snapshot().complete)
    }
    @Test fun genuineCoverageCompletesAt85Percent() {
        val tracker = PlaybackViewingTracker(minute)
        play(tracker, 0, 50_500)
        assertFalse(tracker.snapshot().complete)
        tracker.observe(51_000, minute, 51_000, true, true, 1.0)
        assertTrue(tracker.snapshot().complete)
    }
    @Test fun pausesBufferingAndSuspendedTimersCannotFillAGap() {
        val tracker = PlaybackViewingTracker(minute)
        play(tracker, 0, 5_000)
        tracker.observe(5_000, minute, 5_500, false, true, 1.0)
        tracker.observe(40_000, minute, 40_000, true, true, 1.0)
        tracker.observe(55_000, minute, 55_000, true, true, 1.0)
        assertEquals(5_000, tracker.snapshot().watchedMs)
    }
    @Test fun samplesUnknownRuntimeAndWrongEditionsFailClosed() {
        for (expected in listOf(0, 120_000, 7_200_000)) {
            val tracker = PlaybackViewingTracker(expected.toLong())
            play(tracker, 0, minute)
            assertTrue(tracker.hasPlayed)
            assertEquals(0, tracker.snapshot().watchedMs)
            assertFalse(tracker.snapshot().complete)
        }
    }
    @Test fun roundedDatabaseRuntimeAllowsSmallDifferencesButUsesTheLargerRuntime() {
        val coverage = PlaybackViewingCoverage(59_000, minute, listOf(ViewedRange(0, 50_150)))
        assertTrue(coverage.runtimeMatches)
        assertFalse(coverage.complete)
        assertTrue(coverage.copy(ranges = listOf(ViewedRange(0, 51_000))).complete)
    }
    @Test fun restoredCoverageMergesOverlapsAcrossSessions() {
        val first = PlaybackViewingTracker(minute).also { play(it, 0, 30_000) }
        val second = PlaybackViewingTracker(minute).also { play(it, 25_000, 51_000) }
        second.restore(first.snapshot())
        assertEquals(51_000, second.snapshot().watchedMs)
        assertTrue(second.snapshot().complete)
    }
    @Test fun completedViewingAndDifferentFilesDoNotSeedANewViewing() {
        val tracker = PlaybackViewingTracker(minute).also { play(it, 0, 1_000) }
        tracker.restore(PlaybackViewingCoverage(minute, minute, listOf(ViewedRange(0, minute))))
        tracker.restore(PlaybackViewingCoverage(45_000, minute, listOf(ViewedRange(0, 40_000))))
        assertEquals(1_000, tracker.snapshot().watchedMs)
    }
    @Test fun normalPlaybackAtDoubleSpeedCountsButTimestampJumpsDoNot() {
        val tracker = PlaybackViewingTracker(minute)
        play(tracker, 0, 51_000, rate = 2.0)
        assertTrue(tracker.snapshot().complete)
    }
    @Test fun aChangedMediaDurationDiscardsEvidenceFromThePreviousFile() {
        val tracker = PlaybackViewingTracker(minute).also { play(it, 0, 50_000) }
        tracker.observe(50_500, 120_000, 50_500, true, true, 1.0)
        assertEquals(0, tracker.snapshot().watchedMs)
        assertFalse(tracker.snapshot().complete)
    }
}
