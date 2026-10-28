package com.torve.android.catalog

import com.torve.data.panda.NzbIndexerRow
import com.torve.data.usenet.NewznabItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SportsIndexersTest {
    private val first = SportsIndexer("scenenzbs", "https://treasure-maps.com", "first-key")
    private val second = SportsIndexer("custom", "https://videothek.io/api", "second-key")

    @Test fun recoversEachRedactedKeyFromItsOwnProviderAndKeepsAllProviders() {
        val saved = listOf(NzbIndexerRow("scenenzbs", "", "first-key"),
            NzbIndexerRow("custom", "https://videothek.io", "second-key"))
        val configured = listOf(NzbIndexerRow("scenenzbs", "", "[redacted]"),
            NzbIndexerRow("custom", "https://videothek.io/api", "••••"),
            NzbIndexerRow("custom", "https://missing.example", "[redacted]"))
        val actual = resolveSportsIndexers(configured, saved)
        assertEquals(listOf("first-key", "second-key"), actual.map { it.apiKey })
        assertFalse(actual.toString().contains("first-key"))
        assertFalse(actual.any { it.url.contains("missing") })
    }

    @Test fun startupLoadsAllSavedIndexersButKnownEmptyConfigDoesNotReviveRemovedOnes() {
        val saved = listOf(NzbIndexerRow("scenenzbs", "", "first-key"),
            NzbIndexerRow("custom", "https://videothek.io/api", "second-key"))
        assertEquals(2, resolveSportsIndexers(null, saved).size)
        assertTrue(resolveSportsIndexers(emptyList(), saved).isEmpty())
        assertEquals(1, resolveSportsIndexers(saved.take(1), saved).size)
    }

    @Test fun equivalentApiUrlsAreOnlySearchedOnceAndInvalidKeysAreSkipped() {
        val rows = listOf(NzbIndexerRow("custom", "https://videothek.io", "current"),
            NzbIndexerRow("custom", "https://videothek.io/api/", "duplicate"),
            NzbIndexerRow("custom", "https://other.example", "*****"),
            NzbIndexerRow("custom", "not a url", "key"))
        val resolved = resolveSportsIndexers(rows, emptyList())
        assertEquals(1, resolved.size)
        assertEquals("current", resolved.single().apiKey)
    }

    @Test fun cacheIdentityIncludesEveryProviderAndKeyWithoutDependingOnOrder() {
        val all = sportsIndexersKey(listOf(first, second))
        assertEquals(all, sportsIndexersKey(listOf(second, first)))
        assertNotEquals(all, sportsIndexersKey(listOf(first)))
        assertNotEquals(all, sportsIndexersKey(listOf(first, second.copy(apiKey = "new-key"))))
        assertFalse(all.contains("key"))
    }

    @Test fun matchingGuidsFromDifferentIndexersKeepTheirOwnDownloadUrls() {
        val one = NewznabItem("NBA 2026.10.12 Final", "https://one.example/api?t=get&id=1&apikey=key", "1")
        val two = one.copy(nzbUrl = "https://two.example/api?t=get&id=1&apikey=other")
        val result = SportsIndexerResults(listOf(SportsIndexerResult(first, listOf(one, one)),
            SportsIndexerResult(second, listOf(two))))
        assertEquals(listOf(one.nzbUrl, two.nzbUrl), result.items.map { it.nzbUrl })
        assertNotEquals(one.sportsSourceId(), two.sportsSourceId())
        assertFalse(one.sportsSourceId().contains("apikey"))
    }

    @Test fun providerFailureDoesNotCancelTheOtherProviderOrDiscardItsResults() = runTest {
        val called = mutableSetOf<String>()
        val result = fetchSportsIndexers(listOf(first, second)) {
            called += it.label
            if (it == first) throw IllegalStateException("HTTP 429")
            delay(10)
            listOf(NewznabItem("NBA Final", "https://videothek.io/nzb"))
        }
        assertEquals(setOf("Treasure Maps", "videothek.io"), called)
        assertTrue(result.anySucceeded)
        assertEquals(1, result.items.size)
        assertTrue(result.warning!!.contains("Treasure Maps: HTTP 429"))
    }

    @Test fun respondingProviderIsPublishedBeforeSlowProviderTimesOut() = runTest {
        val published = mutableListOf<String>()
        val result = fetchSportsIndexers(listOf(first, second), timeoutMs = 100, onResult = {
            published += it.indexer.label
        }) {
            if (it == first) delay(10_000)
            listOf(NewznabItem("NBA Final", "https://videothek.io/nzb"))
        }
        assertEquals("videothek.io", published.first())
        assertEquals(1, result.items.size)
        assertTrue(result.warning!!.contains("timed out"))
    }

    @Test fun allFailedProvidersProduceAnErrorRatherThanAnEmptySuccess() = runTest {
        val result = fetchSportsIndexers(listOf(first, second)) { error("HTTP 403") }
        assertFalse(result.anySucceeded)
        assertTrue(result.warning!!.contains("Treasure Maps"))
        assertTrue(result.warning!!.contains("videothek.io"))
    }

    @Test fun cancellationStillCancelsTheRefresh() = runTest {
        try {
            fetchSportsIndexers(listOf(first, second)) { throw CancellationException("Leaving") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
