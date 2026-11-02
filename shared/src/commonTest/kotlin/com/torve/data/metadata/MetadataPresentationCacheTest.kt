package com.torve.data.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MetadataPresentationCacheTest {
    @Test fun appleCannotRestoreRetiredHomeOrCatalogueArtwork() {
        for (key in listOf("home_snapshot_v5", "home_startup_snapshot_v1",
            "catalog_discovery_snapshot_v1_movie", "catalog_discovery_snapshot_v1_tv")) {
            val migrated = metadataPresentationCacheKey(key, "https://api.torve.app/apple-metadata/v1")
            assertNotEquals(key, migrated)
            assertEquals(migrated, metadataPresentationCacheKey(key, "https://api.torve.app/apple-metadata/v1"))
        }
    }

    @Test fun existingPlatformsRetainTheirPresentationKeys() {
        assertEquals("home_snapshot_v5", metadataPresentationCacheKey("home_snapshot_v5", null))
    }
}
