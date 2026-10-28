package com.torve.android.tv.screens

import com.torve.android.tv.components.TvContentRail

/** Preserve the user's configured order while keeping their first resume row
 * ahead of the automatic availability/download rows that otherwise bury it. */
internal fun mergeTvHomePlaybackRails(
    configuredRails: List<TvContentRail>,
    outcomeRails: List<TvContentRail>,
): List<TvContentRail> = if (configuredRails.firstOrNull()?.key == "continue_watching") {
    configuredRails.take(1) + outcomeRails + configuredRails.drop(1)
} else {
    outcomeRails + configuredRails
}
