package com.torve.android.ui.player

internal fun shouldShowPlayerCinematicLoading(
    playbackUrl: String,
    firstFrameAtMs: Long,
    errorMessage: String?,
): Boolean = playbackUrl.isNotBlank() && firstFrameAtMs <= 0L && errorMessage == null
