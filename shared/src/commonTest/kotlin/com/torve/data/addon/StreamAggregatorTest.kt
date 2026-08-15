package com.torve.data.addon

import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamAggregatorTest {

    @Test
    fun parseSizeToBytes_gb() {
        val result = StreamAggregator.parseSizeToBytes("1.5 GB")
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), result)
    }

    @Test
    fun parseSizeToBytes_mb() {
        val result = StreamAggregator.parseSizeToBytes("700 MB")
        assertEquals((700.0 * 1024 * 1024).toLong(), result)
    }

    @Test
    fun parseSizeToBytes_tb() {
        val result = StreamAggregator.parseSizeToBytes("2 TB")
        assertEquals((2.0 * 1024 * 1024 * 1024 * 1024).toLong(), result)
    }

    @Test
    fun parseSizeToBytes_kb() {
        val result = StreamAggregator.parseSizeToBytes("512 KB")
        assertEquals((512.0 * 1024).toLong(), result)
    }

    @Test
    fun parseSizeToBytes_malformed_returns_null() {
        assertNull(StreamAggregator.parseSizeToBytes("not a size"))
    }

    @Test
    fun parseSizeToBytes_empty_returns_null() {
        assertNull(StreamAggregator.parseSizeToBytes(""))
    }

    @Test
    fun parseSizeToBytes_no_unit_returns_null() {
        assertNull(StreamAggregator.parseSizeToBytes("1234"))
    }

    @Test
    fun parseSizeToBytes_case_insensitive() {
        val result = StreamAggregator.parseSizeToBytes("1.5 gb")
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), result)
    }

    @Test
    fun parseSizeToBytes_decimal_gb() {
        val result = StreamAggregator.parseSizeToBytes("4.7 GB")
        assertEquals((4.7 * 1024 * 1024 * 1024).toLong(), result)
    }

    @Test
    fun resolveStreamAddonBaseUrls_ignores_catalog_only_addons_and_keeps_stream_addons() {
        val streamAddon = installedAddon(
            manifestUrl = "https://torrentio.strem.fun/manifest.json",
            resources = listOf("stream"),
        )
        val catalogAddon = installedAddon(
            manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
            resources = listOf("catalog"),
        )

        val urls = resolveStreamAddonBaseUrls(listOf(streamAddon, catalogAddon))

        assertEquals(listOf("https://torrentio.strem.fun"), urls)
    }

    @Test
    fun resolveStreamAddonBaseUrls_returnsEmpty_when_only_non_stream_addons_are_installed() {
        val catalogAddon = installedAddon(
            manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
            resources = listOf("catalog"),
        )

        val urls = resolveStreamAddonBaseUrls(listOf(catalogAddon))

        assertEquals(emptyList(), urls)
    }

    @Test
    fun resolveStreamAddonBaseUrls_preserves_unknown_legacy_addons() {
        val legacyAddon = installedAddon(
            manifestUrl = "https://legacy-addon.example/manifest.json",
            resources = emptyList(),
        )

        val urls = resolveStreamAddonBaseUrls(listOf(legacyAddon))

        assertEquals(listOf("https://legacy-addon.example"), urls)
    }

    @Test
    fun mergeDebridPreflightResults_preservesUnverifiedAndUncheckedCandidates() {
        val verifiedCandidate = parsedStream("verified", "hash-1")
        val transientFailure = parsedStream("transient", "hash-2")
        val beyondPreflightLimit = parsedStream("unchecked", "hash-3")

        val merged = mergeDebridPreflightResults(
            candidates = listOf(verifiedCandidate, transientFailure, beyondPreflightLimit),
            verified = listOf(verifiedCandidate.copy(isCached = true)),
        )

        assertEquals(3, merged.size)
        assertTrue(merged.first { it.infoHash == "hash-1" }.isCached)
        assertFalse(merged.first { it.infoHash == "hash-2" }.isCached)
        assertFalse(merged.first { it.infoHash == "hash-3" }.isCached)
    }

    private fun parsedStream(title: String, infoHash: String): ParsedStream = ParsedStream(
        addonName = "test",
        quality = "1080p",
        title = title,
        infoHash = infoHash,
    )

    private fun installedAddon(
        manifestUrl: String,
        resources: List<String>,
    ): InstalledAddon {
        return InstalledAddon(
            manifestUrl = manifestUrl,
            manifest = AddonManifest(
                id = manifestUrl,
                name = manifestUrl,
                version = "1.0.0",
                resources = resources,
            ),
        )
    }
}
