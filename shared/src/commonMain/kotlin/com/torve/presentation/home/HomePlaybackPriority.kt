package com.torve.presentation.home

import com.torve.domain.model.WatchProgress

/** Input has already been scoped to the active profile and selected media type. */
fun latestHomeResume(progress: List<WatchProgress>): WatchProgress? = progress
    .filter { !it.isContentPlaceholder && it.positionMs > 0 && it.progressPercent > 0.02f && !it.isWatched(.9f) }
    .maxByOrNull { it.updatedAt }
