package com.torve.desktop.ui.detail

import com.torve.desktop.ui.navigation.DesktopDestination
import com.torve.domain.model.MediaItem

enum class DesktopDetailControllerKey {
    LIBRARY,
    SEARCH,
}

enum class DesktopPlaybackOriginSurface {
    ROW,
    DRAWER,
    FULL_DETAIL,
}

data class DesktopPlaybackOrigin(
    val destination: DesktopDestination,
    val controllerKey: DesktopDetailControllerKey,
    val item: MediaItem,
    val itemId: String,
    val surface: DesktopPlaybackOriginSurface,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class DesktopFullDetailRoute(
    val destination: DesktopDestination,
    val controllerKey: DesktopDetailControllerKey,
    val item: MediaItem,
)
