package com.torve.domain.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSegmentStateMachineTest {
    @Test
    fun deterministicTimelineProtectsPostCreditAndStartsAtFinalCredits() {
        val timeline = listOf(
            segment(SegmentType.INTRO, 42_000, 64_000),
            segment(SegmentType.CREDITS, 1_218_000, 1_242_000),
            segment(SegmentType.POST_CREDIT, 1_242_000, 1_267_000),
            segment(SegmentType.FINAL_CREDITS, 1_271_000, 1_292_000),
        )
        val machine = PlaybackSegmentStateMachine()
        val settings = PlaybackSegmentSettings(
            introMode = SegmentActionMode.AUTOMATIC,
            playNextMode = SegmentActionMode.AUTOMATIC,
        )

        val intro = machine.update(42_000, 1_292_000, timeline, settings)
        assertTrue(intro.showSkipAction)
        assertEquals(63_650, machine.automaticSkipTarget(timeline[0], SegmentActionMode.AUTOMATIC))
        assertNull(machine.automaticSkipTarget(timeline[0], SegmentActionMode.AUTOMATIC), "must not seek-loop")

        val firstCredits = machine.update(1_220_000, 1_292_000, timeline, settings)
        assertTrue(firstCredits.showNextPrompt)
        assertTrue(firstCredits.extraSceneRemains)
        assertFalse(firstCredits.allowNextCountdown)

        val postCredit = machine.update(1_250_000, 1_292_000, timeline, settings)
        assertEquals(PlaybackStructuralState.PLAYING_POST_CREDIT, postCredit.structuralState)
        assertFalse(postCredit.showNextPrompt)

        val finalCredits = machine.update(1_275_000, 1_292_000, timeline, settings)
        assertTrue(finalCredits.allowNextCountdown)
        assertTrue(finalCredits.markWatched)
    }

    @Test
    fun backwardSeekIntoIntroSuppressesAutomaticSkipForSession() {
        val intro = segment(SegmentType.INTRO, 40_000, 70_000)
        val machine = PlaybackSegmentStateMachine()
        assertNotNull(machine.automaticSkipTarget(intro, SegmentActionMode.AUTOMATIC))

        val correction = machine.onManualSeek(70_000, 50_000)

        assertEquals(SegmentBoundaryCorrection(intro.id, 50_000), correction)
        assertNull(machine.automaticSkipTarget(intro.copy(id = "second-pass"), SegmentActionMode.AUTOMATIC))
    }

    @Test
    fun resumedPlaybackCanPromptButDoesNotAutomaticallySkipOrTransition() {
        val intro = segment(SegmentType.INTRO, 40_000, 70_000)
        val credits = segment(SegmentType.FINAL_CREDITS, 1_200_000, 1_250_000)
        val machine = PlaybackSegmentStateMachine()
        machine.suppressAutomaticActionsForResume()

        assertNull(machine.automaticSkipTarget(intro, SegmentActionMode.AUTOMATIC))
        val creditsState = machine.update(
            1_210_000,
            1_250_000,
            listOf(credits),
            PlaybackSegmentSettings(playNextMode = SegmentActionMode.AUTOMATIC),
        )
        assertTrue(creditsState.showNextPrompt)
        assertFalse(creditsState.allowNextCountdown)
    }

    @Test
    fun explicitBackwardViewingIntentCanSuppressAutomaticSkipWithoutPriorAutoSeek() {
        val intro = segment(SegmentType.INTRO, 40_000, 70_000)
        val machine = PlaybackSegmentStateMachine()
        machine.suppressAutomaticSkipForSession()

        assertNull(machine.automaticSkipTarget(intro, SegmentActionMode.AUTOMATIC))
        assertTrue(machine.update(50_000, 1_000_000, listOf(intro), PlaybackSegmentSettings()).showSkipAction)
    }

    @Test
    fun multiplePostCreditScenesKeepCountdownBlockedUntilFinalCredits() {
        val timeline = listOf(
            segment(SegmentType.CREDITS, 1_000_000, 1_020_000),
            segment(SegmentType.POST_CREDIT, 1_020_000, 1_040_000),
            segment(SegmentType.CREDITS, 1_040_000, 1_060_000),
            segment(SegmentType.POST_CREDIT, 1_060_000, 1_080_000),
            segment(SegmentType.FINAL_CREDITS, 1_080_000, 1_120_000),
        )
        val settings = PlaybackSegmentSettings(playNextMode = SegmentActionMode.AUTOMATIC)
        val machine = PlaybackSegmentStateMachine()

        assertFalse(machine.update(1_005_000, 1_120_000, timeline, settings).allowNextCountdown)
        assertFalse(machine.update(1_045_000, 1_120_000, timeline, settings).allowNextCountdown)
        assertEquals(PlaybackStructuralState.PLAYING_POST_CREDIT, machine.update(1_070_000, 1_120_000, timeline, settings).structuralState)
        assertTrue(machine.update(1_090_000, 1_120_000, timeline, settings).allowNextCountdown)
    }

    @Test
    fun creditsOverlayNeverPromptsAndFallsBackToPercentageForWatched() {
        val overlay = segment(SegmentType.CONTENT, 1_100_000, 1_200_000).copy(creditsOverlay = true)
        val state = PlaybackSegmentStateMachine().update(
            1_150_000,
            1_300_000,
            listOf(overlay),
            PlaybackSegmentSettings(playNextMode = SegmentActionMode.AUTOMATIC),
        )

        assertFalse(state.showNextPrompt)
        assertTrue(state.markWatched) // legacy fallback remains when no structural end exists
    }

    @Test
    fun mediumConfidenceCanShowButtonButCannotAutoSkip() {
        val intro = segment(SegmentType.INTRO, 10_000, 30_000, confidence = 0.7)
        val machine = PlaybackSegmentStateMachine()
        val state = machine.update(15_000, 1_000_000, listOf(intro), PlaybackSegmentSettings(introMode = SegmentActionMode.AUTOMATIC))

        assertTrue(state.showSkipAction)
        assertNull(machine.automaticSkipTarget(intro, SegmentActionMode.AUTOMATIC))
        assertEquals(28_500, machine.manualSkipTarget(intro))
    }

    private fun segment(type: SegmentType, start: Long, end: Long, confidence: Double = 0.95) = PlaybackSegment(
        id = "$type:$start",
        type = type,
        startMs = start,
        endMs = end,
        confidence = confidence,
        confidenceLevel = if (confidence >= 0.88) SegmentConfidence.VERY_HIGH else SegmentConfidence.MEDIUM,
        source = SegmentEvidenceSource.CONSENSUS,
        evidence = emptyList(),
        createdAtEpochMs = 0,
        updatedAtEpochMs = 0,
        version = 1,
    )
}
