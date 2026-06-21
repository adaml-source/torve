package com.torve.presentation.detail

import com.torve.data.addon.ParsedStream

enum class PlaybackStartupPhase {
    IDLE,
    LOADING_STARTUP_CANDIDATES,
    STARTUP_CANDIDATES_AVAILABLE,
    ATTEMPTING_STARTUP_AUTOPLAY,
    STARTUP_CANDIDATE_FAILED,
    FALLING_BACK_TO_FULL_FETCH,
    FULL_RESULTS_AVAILABLE,
}

data class PlaybackStartupStatus(
    val phase: PlaybackStartupPhase = PlaybackStartupPhase.IDLE,
    val startupCandidateCount: Int = 0,
    val mergedResultCount: Int = 0,
)

sealed interface PlaybackStartupEvent {
    data object LoadingStartupCandidates : PlaybackStartupEvent
    data class StartupCandidatesAvailable(val candidateCount: Int) : PlaybackStartupEvent
    data object AttemptingStartupAutoplay : PlaybackStartupEvent
    data object StartupCandidateFailed : PlaybackStartupEvent
    data object FallingBackToFullFetch : PlaybackStartupEvent
    data class FullResultsAvailable(val mergedResultCount: Int) : PlaybackStartupEvent
}

object DetailPlaybackStartupOrchestrator {
    fun reduce(
        current: PlaybackStartupStatus,
        event: PlaybackStartupEvent,
    ): PlaybackStartupStatus {
        return when (event) {
            PlaybackStartupEvent.LoadingStartupCandidates -> PlaybackStartupStatus(
                phase = PlaybackStartupPhase.LOADING_STARTUP_CANDIDATES,
            )

            is PlaybackStartupEvent.StartupCandidatesAvailable -> current.copy(
                phase = PlaybackStartupPhase.STARTUP_CANDIDATES_AVAILABLE,
                startupCandidateCount = event.candidateCount,
                mergedResultCount = event.candidateCount,
            )

            PlaybackStartupEvent.AttemptingStartupAutoplay -> current.copy(
                phase = PlaybackStartupPhase.ATTEMPTING_STARTUP_AUTOPLAY,
            )

            PlaybackStartupEvent.StartupCandidateFailed -> current.copy(
                phase = PlaybackStartupPhase.STARTUP_CANDIDATE_FAILED,
            )

            PlaybackStartupEvent.FallingBackToFullFetch -> current.copy(
                phase = PlaybackStartupPhase.FALLING_BACK_TO_FULL_FETCH,
            )

            is PlaybackStartupEvent.FullResultsAvailable -> current.copy(
                phase = PlaybackStartupPhase.FULL_RESULTS_AVAILABLE,
                mergedResultCount = event.mergedResultCount,
            )
        }
    }

    fun mergeStreams(
        startupStreams: List<ParsedStream>,
        fullStreams: List<ParsedStream>,
        keySelector: (ParsedStream) -> String,
    ): List<ParsedStream> {
        if (startupStreams.isEmpty()) return fullStreams
        if (fullStreams.isEmpty()) return startupStreams

        val orderedKeys = mutableListOf<String>()
        val mergedByKey = linkedMapOf<String, ParsedStream>()

        startupStreams.forEach { stream ->
            val key = keySelector(stream)
            if (key !in mergedByKey) {
                orderedKeys += key
            }
            mergedByKey[key] = stream
        }

        fullStreams.forEach { stream ->
            val key = keySelector(stream)
            if (key !in mergedByKey) {
                orderedKeys += key
            }
            mergedByKey[key] = stream
        }

        return orderedKeys.mapNotNull(mergedByKey::get)
    }
}
