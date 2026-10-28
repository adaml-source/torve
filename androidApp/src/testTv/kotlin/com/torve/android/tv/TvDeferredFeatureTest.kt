package com.torve.android.tv

import org.junit.Assert.*
import org.junit.Test

class TvDeferredFeatureTest {
    @Test fun enrichingOneTitleDoesNotInvalidateUnrelatedPosters() {
        val first = com.torve.domain.model.MediaItem(id = "cache-observer-a", type = com.torve.domain.model.MediaType.MOVIE, title = "A")
        val second = first.copy(id = "cache-observer-b", title = "B")
        val changed = mutableSetOf<String>()
        // Standalone snapshot tests need the initialization boundary that the
        // real Compose recomposer supplies before a background update arrives.
        com.torve.android.tv.components.withCachedTvBrowsePreviewEnrichment(first)
        com.torve.android.tv.components.withCachedTvBrowsePreviewEnrichment(second)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        val observer = androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads("first", { changed += it }) { com.torve.android.tv.components.withCachedTvBrowsePreviewEnrichment(first) }
            observer.observeReads("second", { changed += it }) { com.torve.android.tv.components.withCachedTvBrowsePreviewEnrichment(second) }
            com.torve.android.tv.components.cacheTvBrowsePreviewEnrichedItem(first.copy(overview = "Arrived in the background"))
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            assertEquals(setOf("first"), changed)
            assertEquals("Arrived in the background", com.torve.android.tv.components.withCachedTvBrowsePreviewEnrichment(first).overview)
        } finally { observer.stop(); observer.clear() }
    }
    @Test fun startupAndRefreshSignalsDoNotCreateAnUnopenedCatalogue() {
        var creates = 0
        val feature = TvDeferredFeature { creates++; Any() }
        repeat(20) { feature.ifOpened { error("An unopened feature received a refresh") } }
        assertEquals(0, creates)
        val opened = feature.get()
        repeat(20) { feature.ifOpened { assertSame(opened, it) }; assertSame(opened, feature.get()) }
        assertEquals(1, creates)
    }
}
