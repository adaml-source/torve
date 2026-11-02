package com.torve.presentation.home

import com.torve.domain.model.CatalogShelf
import kotlin.test.*

class NativeCatalogueSnapshotTest {
    @Test fun oldAddonPageCannotSeedNewPaginationButOtherHomeRowsRemain() {
        val rows = listOf("provider-tmdb.language:movie", "provider-tmdb.year_series", "other-movies")
            .map { CatalogShelf(it, it, emptyList()) }
        assertEquals(listOf("other-movies"), rows.migratedNativeCatalogueShelves(0, true).map { it.id })
        assertEquals(rows, rows.migratedNativeCatalogueShelves(2, true))
        assertEquals(rows, rows.migratedNativeCatalogueShelves(0, false))
    }
    @Test fun revisionOneAlsoRetiresWrappedTmdbPagesBeforeRestoringHome() {
        val rows = listOf("aio-78de3b0.tmdb.trending:series", "aio-78de3b0.tmdb.language_movie", "aio-78de3b0.trakt.trending:series", "library")
            .map { CatalogShelf(it, it, emptyList()) }
        assertEquals(listOf("aio-78de3b0.trakt.trending:series", "library"), rows.migratedNativeCatalogueShelves(1, true).map { it.id })
        assertEquals(rows, rows.migratedNativeCatalogueShelves(2, true))
        assertEquals(rows, rows.migratedNativeCatalogueShelves(1, false))
    }
}
