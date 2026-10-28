package com.torve.data.addon

import com.torve.domain.model.AddonCatalog
import com.torve.domain.model.AddonManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CatalogueShelfIdentityTest {
    @Test
    fun aiostreamsMovieAndTvCataloguesWithTheSameIdDoNotShareRenderOrEnrichmentIdentity() {
        val movies = AddonCatalog(id = "tmdb.top", type = "movie", name = "Popular")
        val shows = movies.copy(type = "series")
        val manifest = AddonManifest(id = "provider", name = "Provider", version = "1", catalogs = listOf(movies, shows))
        assertNotEquals(manifest.catalogueShelfId(movies), manifest.catalogueShelfId(shows))
        assertEquals("provider-tmdb.top", manifest.catalogueShelfId(movies).legacyAddonShelfId())
        assertEquals("provider-tmdb.top", manifest.catalogueShelfId(shows).legacyAddonShelfId())
    }

    @Test
    fun alreadyUniqueCatalogueIdsKeepTheirSavedLayoutKeys() {
        val movies = AddonCatalog(id = "movies", type = "movie")
        val shows = AddonCatalog(id = "shows", type = "series")
        val manifest = AddonManifest(id = "provider", name = "Provider", version = "1", catalogs = listOf(movies, shows))
        assertEquals("provider-movies", manifest.catalogueShelfId(movies))
        assertEquals("provider-shows", manifest.catalogueShelfId(shows))
    }
}
