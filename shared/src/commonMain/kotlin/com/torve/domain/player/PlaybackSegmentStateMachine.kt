package com.torve.domain.player

enum class PlaybackStructuralState {
    PLAYING_CONTENT,
    PLAYING_RECAP,
    PLAYING_INTRO,
    PLAYING_CREDITS,
    PLAYING_POST_CREDIT,
    PLAYING_FINAL_CREDITS,
    PLAYING_PREVIEW,
    NEXT_PROMPT_VISIBLE,
    NEXT_COUNTDOWN,
    TRANSITIONING_TO_NEXT,
}

data class PlaybackSegmentUiState(
    val structuralState: PlaybackStructuralState = PlaybackStructuralState.PLAYING_CONTENT,
    val activeActionSegment: PlaybackSegment? = null,
    val showSkipAction: Boolean = false,
    val showNextPrompt: Boolean = false,
    val allowNextCountdown: Boolean = false,
    val extraSceneRemains: Boolean = false,
    val markWatched: Boolean = false,
)

data class SegmentBoundaryCorrection(
    val segmentId: String,
    val suggestedEndMs: Long,
)

class PlaybackSegmentStateMachine(
    private val config: PlaybackSegmentConfig = PlaybackSegmentConfig(),
) {
    private val dismissedSegmentIds = mutableSetOf<String>()
    private val autoHandledSegmentIds = mutableSetOf<String>()
    private var automaticSkipSuppressed = false
    private var automaticNextSuppressed = false
    private var lastHandledSkip: PlaybackSegment? = null
    private var nextPromptCancelled = false

    fun update(
        positionMs: Long,
        durationMs: Long,
        segments: List<PlaybackSegment>,
        settings: PlaybackSegmentSettings,
    ): PlaybackSegmentUiState {
        if (!settings.smartDetectionEnabled) return PlaybackSegmentUiState(markWatched = fallbackWatched(positionMs, durationMs))
        val active = segments.filter { it.contains(positionMs) }.maxByOrNull(PlaybackSegment::confidence)
        val structural = active?.type?.toStructuralState()
        val protectedAhead = segments.any {
            it.type == SegmentType.POST_CREDIT && it.endMs > positionMs && it.confidence >= config.mediumThreshold
        }
        val finalContentEnd = finalMeaningfulContentEnd(segments)
        val watched = finalContentEnd?.let { positionMs >= it } ?: fallbackWatched(positionMs, durationMs)
        val isCredits = active?.type in setOf(SegmentType.CREDITS, SegmentType.FINAL_CREDITS)
        val creditActionable = active != null && !active.creditsOverlay && active.confidence >= config.manualActionThreshold
        val showNext = isCredits && creditActionable && !nextPromptCancelled && settings.playNextMode != SegmentActionMode.OFF
        val countdown = showNext && settings.playNextMode == SegmentActionMode.AUTOMATIC &&
            active!!.confidence >= config.automaticActionThreshold &&
            !automaticNextSuppressed &&
            (!settings.protectPostCreditScenes || !protectedAhead || active.type == SegmentType.FINAL_CREDITS)
        val mode = when (active?.type) {
            SegmentType.INTRO -> settings.introMode
            SegmentType.RECAP -> settings.recapMode
            else -> SegmentActionMode.OFF
        }
        val showSkip = active != null && active.type in setOf(SegmentType.INTRO, SegmentType.RECAP) &&
            active.id !in dismissedSegmentIds && mode != SegmentActionMode.OFF &&
            active.confidence >= config.manualActionThreshold
        return PlaybackSegmentUiState(
            structuralState = when {
                countdown -> PlaybackStructuralState.NEXT_COUNTDOWN
                showNext -> PlaybackStructuralState.NEXT_PROMPT_VISIBLE
                else -> structural ?: PlaybackStructuralState.PLAYING_CONTENT
            },
            activeActionSegment = active?.takeIf { showSkip || showNext },
            showSkipAction = showSkip,
            showNextPrompt = showNext,
            allowNextCountdown = countdown,
            extraSceneRemains = protectedAhead,
            markWatched = watched,
        )
    }

    fun automaticSkipTarget(segment: PlaybackSegment, mode: SegmentActionMode): Long? {
        if (mode != SegmentActionMode.AUTOMATIC || automaticSkipSuppressed) return null
        if (segment.id in autoHandledSegmentIds || segment.confidence < config.automaticActionThreshold) return null
        if (segment.type !in setOf(SegmentType.INTRO, SegmentType.RECAP)) return null
        autoHandledSegmentIds += segment.id
        lastHandledSkip = segment
        return config.skipTarget(segment)
    }

    fun manualSkipTarget(segment: PlaybackSegment): Long? {
        if (segment.confidence < config.manualActionThreshold) return null
        dismissedSegmentIds += segment.id
        lastHandledSkip = segment
        return config.skipTarget(segment)
    }

    fun onManualSeek(fromMs: Long, toMs: Long): SegmentBoundaryCorrection? {
        val skipped = lastHandledSkip
        val isSkipUndo = toMs < fromMs && skipped != null && skipped.contains(toMs) &&
            fromMs <= skipped.endMs + config.correctionRecognitionWindowMs
        if (isSkipUndo) {
            automaticSkipSuppressed = true
            lastHandledSkip = null
        }
        if (toMs < fromMs) {
            nextPromptCancelled = true
            automaticNextSuppressed = true
        }
        return skipped?.takeIf { isSkipUndo }?.let {
            SegmentBoundaryCorrection(segmentId = it.id, suggestedEndMs = toMs)
        }
    }

    fun suppressAutomaticActionsForResume() {
        automaticSkipSuppressed = true
        automaticNextSuppressed = true
    }

    fun suppressAutomaticSkipForSession() {
        automaticSkipSuppressed = true
    }

    fun dismiss(segment: PlaybackSegment) { dismissedSegmentIds += segment.id }

    fun cancelNextPrompt() { nextPromptCancelled = true }

    fun resetForNewEpisode() {
        dismissedSegmentIds.clear()
        autoHandledSegmentIds.clear()
        automaticSkipSuppressed = false
        automaticNextSuppressed = false
        lastHandledSkip = null
        nextPromptCancelled = false
    }

    private fun finalMeaningfulContentEnd(segments: List<PlaybackSegment>): Long? {
        val finalCredits = segments.filter { it.type == SegmentType.FINAL_CREDITS && !it.creditsOverlay }
            .filter { it.confidence >= config.highThreshold }.minByOrNull(PlaybackSegment::startMs)
        if (finalCredits != null) return finalCredits.startMs
        val protected = segments.filter { it.type == SegmentType.POST_CREDIT }
            .filter { it.confidence >= config.mediumThreshold }.maxOfOrNull(PlaybackSegment::endMs)
        if (protected != null) return protected
        return segments.filter { it.type == SegmentType.CREDITS && !it.creditsOverlay }
            .filter { it.confidence >= config.highThreshold }.minOfOrNull(PlaybackSegment::startMs)
    }

    private fun fallbackWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs.toDouble() / durationMs >= 0.85

    private fun SegmentType.toStructuralState(): PlaybackStructuralState = when (this) {
        SegmentType.RECAP -> PlaybackStructuralState.PLAYING_RECAP
        SegmentType.INTRO -> PlaybackStructuralState.PLAYING_INTRO
        SegmentType.CREDITS -> PlaybackStructuralState.PLAYING_CREDITS
        SegmentType.POST_CREDIT -> PlaybackStructuralState.PLAYING_POST_CREDIT
        SegmentType.FINAL_CREDITS -> PlaybackStructuralState.PLAYING_FINAL_CREDITS
        SegmentType.NEXT_EPISODE_PREVIEW -> PlaybackStructuralState.PLAYING_PREVIEW
        else -> PlaybackStructuralState.PLAYING_CONTENT
    }
}
