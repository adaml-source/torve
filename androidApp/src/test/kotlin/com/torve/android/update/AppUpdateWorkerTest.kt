package com.torve.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun updateDiagnosticsRedactQueryAndFragmentFromResolvedAssetUrl() {
        assertEquals(
            "https://torve.app/downloads/android/torve.apk",
            redactUpdateUrl("https://torve.app/downloads/android/torve.apk?token=secret#fragment"),
        )
    }

    @Test
    fun updaterFollowsEveryStandardDownloadRedirectButNotOrdinaryResponses() {
        assertTrue(isUpdateRedirectStatus(301))
        assertTrue(isUpdateRedirectStatus(302))
        assertTrue(isUpdateRedirectStatus(303))
        assertTrue(isUpdateRedirectStatus(307))
        assertTrue(isUpdateRedirectStatus(308))
        assertFalse(isUpdateRedirectStatus(200))
        assertFalse(isUpdateRedirectStatus(404))
    }

    @Test
    fun semanticVersionComparisonDoesNotUseLexicalOrdering() {
        assertTrue(isNewerRelease("1.1.10", "1.1.9"))
        assertTrue(isNewerRelease("1.2.0", "1.1.99"))
        assertFalse(isNewerRelease("1.1.4", "1.1.4"))
        assertFalse(isNewerRelease("1.1.3", "1.1.4"))
        assertTrue(isNewerRelease("1.2.4", "1.2.4", candidateVersionCode = 20_118, installedVersionCode = 20_117))
        assertFalse(isNewerRelease("1.2.4", "1.2.4", candidateVersionCode = 20_117, installedVersionCode = 20_117))
    }

    @Test
    fun onlyAvailableNewerTrustedReleaseIsOffered() {
        val manifest = manifest(version = "1.2.0", status = "available")
        val update = parseAvailableUpdate(manifest, "fire_tv", "1.1.4", installedVersionCode = 20_116)

        assertEquals("1.2.0", update?.version)
        assertEquals("https://torve.app/downloads/android/torve-fire-tv-1.2.0.apk", update?.downloadUrl)
        assertEquals(TEST_SHA256, update?.sha256)
        assertEquals(92_000_000L, update?.sizeBytes)
        assertEquals(20_117L, update?.versionCode)
        assertNull(parseAvailableUpdate(manifest, "fire_tv", "1.2.0", installedVersionCode = 20_117))
        assertTrue(
            parseAvailableUpdate(
                manifest(version = "1.2.0", status = "available", versionCode = 20_118),
                "fire_tv",
                "1.2.0",
                installedVersionCode = 20_117,
            ) != null,
        )
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

    @Test
    fun strictReleaseParsingDistinguishesNoUpdateFromBrokenReleaseMetadata() {
        assertNull(
            parseAvailableUpdateStrict(
                manifest(version = "1.2.0", status = "unavailable"),
                "fire_tv",
                "1.1.4",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseAvailableUpdateStrict("{}", "fire_tv", "1.1.4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseAvailableUpdateStrict(
                manifest(version = "1.2.0", status = "available", sha256 = ""),
                "fire_tv",
                "1.1.4",
            )
        }
    }

    private fun manifest(
        version: String,
        status: String,
        host: String = "torve.app",
        sha256: String = TEST_SHA256,
        versionCode: Long = 20_117,
    ): String =
        """{"channels":{"stable":{"fire_tv":{"version":"$version","version_code":$versionCode,"url":"https://$host/downloads/android/torve-fire-tv-$version.apk","sha256":"$sha256","size_bytes":92000000,"status":"$status"}}}}"""

    private companion object {
        const val TEST_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
