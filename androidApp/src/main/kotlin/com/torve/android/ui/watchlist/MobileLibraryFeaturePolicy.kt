package com.torve.android.ui.watchlist

import com.torve.presentation.settings.OptionalAppFeatures

internal enum class MobileLibraryTab { WATCHLIST, FAVORITES, REQUESTS, DOWNLOADS, JELLYFIN }

internal fun mobileLibraryTabs(features: OptionalAppFeatures, loaded: Boolean, jellyfinConnected: Boolean): List<MobileLibraryTab> =
    MobileLibraryTab.entries.filter { tab ->
        when (tab) {
            MobileLibraryTab.REQUESTS -> loaded && features.arrStack
            MobileLibraryTab.JELLYFIN -> loaded && features.jellyfin && jellyfinConnected
            else -> true
        }
    }

internal fun restoreMobileLibraryTab(savedCanonicalIndex: Int, available: List<MobileLibraryTab>): MobileLibraryTab =
    MobileLibraryTab.entries.getOrNull(savedCanonicalIndex)?.takeIf { it in available }
        ?: MobileLibraryTab.WATCHLIST
