package com.torve.android.tv.screens

import com.torve.presentation.detail.DetailUiState

/**
 * A terminal lookup error always wins over stale in-flight flags. This keeps a
 * failed lookup from replacing the detail page with an indefinite cinematic
 * loading layer while the error is being surfaced.
 */
internal fun DetailUiState.hasTerminalSourceFailure(): Boolean =
    resolvedStream == null &&
        streams.isEmpty() &&
        (!streamsError.isNullOrBlank() || !resolveError.isNullOrBlank())

internal fun DetailUiState.shouldShowCinematicSourceLoading(
    sourcePickerVisible: Boolean,
): Boolean =
    preparing == null &&
        !sourcePickerVisible &&
        (isLoadingStreams || isResolving) &&
        !hasTerminalSourceFailure()

internal fun terminalEpisodeFocusTarget(
    state: DetailUiState,
    sourcePickerOriginEpisode: Pair<Int, Int>?,
    resolvingEpisodeTarget: Pair<Int, Int>?,
): Pair<Int, Int>? = if (state.hasTerminalSourceFailure()) {
    sourcePickerOriginEpisode ?: resolvingEpisodeTarget
} else {
    null
}
