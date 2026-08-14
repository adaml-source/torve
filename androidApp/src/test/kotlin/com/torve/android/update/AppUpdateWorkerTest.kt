package com.torve.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateWorkerTest {
    @Test
    fun onlyAmazonBuildsUseThePublishedVpsChannel() {
        assertTrue(usesVpsReleaseUpdates("amazonTv"))
        assertFalse(usesVpsReleaseUpdates("amazonMobile"))
        assertFalse(usesVpsReleaseUpdates("googleTv"))
        assertFalse(usesVpsReleaseUpdates("googleMobile"))
        assertEquals("fire_tv", releasePlatformForFlavor("amazonTv"))
    }

    @Test
    fun semanticVersionComparisonDoesNotUseLexicalOrdering() {
        assertTrue(isNewerRelease("1.1.10", "1.1.9"))
        assertTrue(isNewerRelease("1.2.0", "1.1.99"))
        assertFalse(isNewerRelease("1.1.4", "1.1.4"))
        assertFalse(isNewerRelease("1.1.3", "1.1.4"))
    }

    @Test
    fun onlyAvailableNewerTrustedReleaseIsOffered() {
        val manifest = manifest(version = "1.2.0", status = "available")
        val update = parseAvailableUpdate(manifest, "fire_tv", "1.1.4")

        assertEquals("1.2.0", update?.version)
        assertEquals("https://torve.app/downloads/android/torve-fire-tv-1.2.0.apk", update?.downloadUrl)
        assertEquals(TEST_SHA256, update?.sha256)
        assertEquals(92_000_000L, update?.sizeBytes)
        assertNull(parseAvailableUpdate(manifest, "fire_tv", "1.2.0"))
        assertNull(parseAvailableUpdate(manifest(version = "1.2.0", status = "unavailable"), "fire_tv", "1.1.4"))
        assertNull(
            parseAvailableUpdate(
                manifest(version = "1.2.0", status = "available", host = "example.invalid"),
                "fire_tv",
                "1.1.4",
            ),
        )
        assertNull(parseAvailableUpdate(manifest(version = "1.2.0", status = "available", sha256 = ""), "fire_tv", "1.1.4"))
    }

    private fun manifest(
        version: String,
        status: String,
        host: String = "torve.app",
        sha256: String = TEST_SHA256,
    ): String =
        """{"channels":{"stable":{"fire_tv":{"version":"$version","url":"https://$host/downloads/android/torve-fire-tv-$version.apk","sha256":"$sha256","size_bytes":92000000,"status":"$status"}}}}"""

    private companion object {
        const val TEST_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
