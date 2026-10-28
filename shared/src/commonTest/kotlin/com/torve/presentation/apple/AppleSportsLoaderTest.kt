package com.torve.presentation.apple

import com.torve.data.panda.NzbIndexerRow
import com.torve.data.usenet.NewznabClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppleSportsLoaderTest {
    private val first = NzbIndexerRow("scenenzbs", "https://treasure.example", "first-secret")
    private val second = NzbIndexerRow("custom", "https://other.example/api", "second-secret")
    private fun item(host: String) = "<item><title>NBA.2026.10.12.Lakers.vs.Celtics</title><guid>same</guid><link>https://$host/event</link></item>"
    private fun feed(items: String = "") = "<rss><channel>$items</channel></rss>"

    @Test fun failingFirstIndexerDoesNotCancelSecondAndKeepsItsError() = runTest {
        val called = mutableSetOf<String>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            called += it.url.host
            if (it.url.host == "treasure.example") respond("limited", HttpStatusCode.TooManyRequests)
            else { delay(10); respond(feed(if (it.url.parameters["offset"] == "0") item(it.url.host) else "")) }
        }
        }))
        try {
            val result = loadAppleSports(NewznabClient(http), listOf(first, second), emptyList(), 30)
            assertEquals(setOf("treasure.example", "other.example"), called)
            assertEquals(listOf("https://other.example/event"), result.items.map { it.nzbUrl })
            assertFalse(result.allFailed)
            assertTrue(result.warning!!.contains("Treasure Maps:"))
            assertTrue(result.warning!!.contains("HTTP 429"))
            assertFalse(result.warning!!.contains(first.apiKey))
        } finally { http.close() }
    }

    @Test fun sameGuidsFromDifferentProvidersKeepBothDownloadLinksAndOwnCredentials() = runTest {
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            assertEquals(if (it.url.host == "treasure.example") first.apiKey else second.apiKey, it.url.parameters["apikey"])
            respond(feed(if (it.url.parameters["offset"] == "0") item(it.url.host) else ""))
        }
        }))
        try {
            val result = loadAppleSports(NewznabClient(http), listOf(first, second), emptyList(), 30)
            assertEquals(2, result.items.size)
            assertNull(result.warning)
        } finally { http.close() }
    }

    @Test fun latePageFailureRetainsPagesAlreadyReceived() = runTest {
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            if (it.url.parameters["offset"] == "0") respond(feed(item(it.url.host)))
            else respond("limited", HttpStatusCode.Forbidden)
        }
        }))
        try {
            val result = loadAppleSports(NewznabClient(http), listOf(first), emptyList(), 30)
            assertEquals(1, result.items.size)
            assertTrue(result.warning!!.contains("HTTP 403"))
        } finally { http.close() }
    }

    @Test fun slowIndexerTimesOutWithoutDiscardingOtherResults() = runTest {
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            if (it.url.host == "treasure.example") delay(10_000)
            respond(feed(if (it.url.parameters["offset"] == "0") item(it.url.host) else ""))
        }
        }))
        try {
            val result = loadAppleSports(NewznabClient(http), listOf(first, second), emptyList(), 30, timeoutMs = 100)
            assertEquals(listOf("https://other.example/event"), result.items.map { it.nzbUrl })
            assertTrue(result.warning!!.contains("timed out"))
        } finally { http.close() }
    }

    @Test fun allFailuresRemainDistinguishableFromEmptySuccessfulFeeds() = runTest {
        var failing = true
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            if (failing) respond("denied", HttpStatusCode.Unauthorized) else respond(feed())
        }
        }))
        try {
            val failed = loadAppleSports(NewznabClient(http), listOf(first, second), emptyList(), 30)
            assertTrue(failed.allFailed)
            assertTrue(failed.warning!!.contains("Treasure Maps:"))
            assertTrue(failed.warning!!.contains("other.example:"))
            failing = false
            val recovered = loadAppleSports(NewznabClient(http), listOf(first, second), emptyList(), 30)
            assertFalse(recovered.allFailed)
            assertNull(recovered.warning)
        } finally { http.close() }
    }

    @Test fun cancellationPropagatesRatherThanBecomingProviderError() = runTest {
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { throw CancellationException("Leaving Sports") }
        }))
        try {
            assertFailsWith<CancellationException> { loadAppleSports(NewznabClient(http), listOf(first), emptyList(), 30) }
        } finally { http.close() }
    }

    @Test fun searchesStayInTvCategoriesAndIncludeLocalizedSportsBrowseCategories() = runTest {
        val categories = mutableListOf<String?>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            assertEquals("30", it.url.parameters["maxage"])
            categories += it.url.parameters["cat"]
            respond(feed())
        }
        }))
        try {
            loadAppleSports(NewznabClient(http), listOf(first), listOf("NBA"), 30)
            assertEquals(listOf("5060,5160,5260,5360", "5000,5100,5200,5300"), categories.map { it.orEmpty() })
        } finally { http.close() }
    }
}
