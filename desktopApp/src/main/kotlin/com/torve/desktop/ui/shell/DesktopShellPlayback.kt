package com.torve.desktop.ui.shell

import com.torve.desktop.playback.DesktopPlaybackRequest
import com.torve.desktop.search.DesktopSearchController
import com.torve.desktop.search.DesktopSearchDetailUiState
import com.torve.desktop.ui.detail.DesktopDetailControllerKey
import com.torve.desktop.ui.detail.DesktopFullDetailRoute
import com.torve.desktop.ui.detail.DesktopPlaybackOrigin
import com.torve.desktop.ui.detail.DesktopPlaybackOriginSurface
import com.torve.desktop.ui.navigation.DesktopDestination

data class DesktopSourcePickerRoute(
    val request: DesktopPlaybackRequest,
    val origin: DesktopPlaybackOrigin,
)

fun playbackSourceSurfaceLabel(
    destination: DesktopDestination,
    surface: DesktopPlaybackOriginSurface,
): String = when (surface) {
    DesktopPlaybackOriginSurface.ROW -> "${destination.label} row"
    DesktopPlaybackOriginSurface.DRAWER -> "${destination.label} preview"
    DesktopPlaybackOriginSurface.FULL_DETAIL -> "${destination.label} detail"
}

fun DesktopPlaybackRequest.matches(
    other: DesktopPlaybackRequest?,
): Boolean {
    if (other == null) return false
    return mediaId == other.mediaId &&
        mediaType == other.mediaType &&
        seasonNumber == other.seasonNumber &&
        episodeNumber == other.episodeNumber
}

fun restorePlaybackOrigin(
    origin: DesktopPlaybackOrigin,
    libraryDetailController: DesktopSearchController,
    libraryDetailState: DesktopSearchDetailUiState,
    searchController: DesktopSearchController,
    searchDetailState: DesktopSearchDetailUiState,
    onOpenFullDetail: (DesktopFullDetailRoute) -> Unit,
    onCloseFullDetail: () -> Unit,
) {
    val controller = when (origin.controllerKey) {
        DesktopDetailControllerKey.LIBRARY -> libraryDetailController
        DesktopDetailControllerKey.SEARCH -> searchController
    }
    val state = when (origin.controllerKey) {
        DesktopDetailControllerKey.LIBRARY -> libraryDetailState
        DesktopDetailControllerKey.SEARCH -> searchDetailState
    }
    val selectionAlreadyRestored = state.selectedResult?.id == origin.itemId &&
        state.selectedSeasonNumber == origin.seasonNumber &&
        state.selectedEpisodeNumber == origin.episodeNumber

    if (!selectionAlreadyRestored) {
        controller.selectResult(
            item = origin.item,
            seasonNumber = origin.seasonNumber,
            episodeNumber = origin.episodeNumber,
        )
    }

    if (origin.surface == DesktopPlaybackOriginSurface.FULL_DETAIL) {
        onOpenFullDetail(
            DesktopFullDetailRoute(
                destination = origin.destination,
                controllerKey = origin.controllerKey,
                item = origin.item,
            ),
        )
    } else {
        onCloseFullDetail()
    }
}
