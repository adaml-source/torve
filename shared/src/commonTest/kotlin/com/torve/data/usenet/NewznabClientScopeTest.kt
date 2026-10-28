package com.torve.data.usenet

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.CancellationException

class NewznabClientScopeTest {
    @Test fun acceptsSiteAndApiUrlsWithoutDuplicatingTheApiPath() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("test-key +&", request.url.parameters["apikey"])
            assertEquals("search", request.url.parameters["t"])
            assertEquals(1, request.url.parameters.getAll("apikey")?.size)
            respond("<rss><channel/></rss>")
        })
        try {
            val urls = listOf("https://indexer.example", "https://indexer.example/api",
                "https://indexer.example/api/", "https://indexer.example/newznab/api.php",
                "https://indexer.example/newznab", "https://indexer.example/api?apikey=old&t=caps#docs")
            urls.forEach { NewznabClient(http).browse(it, "test-key +&", "5060") }
            assertEquals(listOf("/api", "/api", "/api", "/newznab/api.php", "/newznab/api", "/api"), paths)
        } finally { http.close() }
    }

    @Test fun indexerRejectionsKeepTheirStatusWithoutExposingCredentials() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound, HttpStatusCode.TooManyRequests)) {
            val http = HttpClient(MockEngine { respond("sensitive response test-secret", status) })
            try {
                val failure = assertFailsWith<IllegalStateException> {
                    NewznabClient(http).browse("https://indexer.example/api", "test-secret", "5060")
                }
                assertTrue(failure.message.orEmpty().contains("HTTP ${status.value}"))
                assertFalse(failure.message.orEmpty().contains("test-secret"))
            } finally { http.close() }
        }
    }

    @Test
    fun releaseTitlesDecodeUnicodeWithoutChangingEncodedDownloadLinksOrIdentifiers() = runTest {
        val http = HttpClient(MockEngine {
            respond("""
                <rss><channel>
                <item><title>Bayern.M%C3%BCnchen.vs.Fenerbah&ccedil;e.1080p</title>
                <guid>match%2B123</guid><link>https://indexer.example/get/Bayern%20M%C3%BCnchen?id=a%2Bb&amp;key=test-key</link></item>
                <item><title><![CDATA[São.Paulo.vs.東京.&#x1F3C0;]]></title>
                <link>https://indexer.example/second</link></item>
                <item><title>Euroleague.24-25.Bayern.M.C3.BCnchen.vs.Maccabi</title>
                <guid>raw-C3-BC-id</guid><link>https://indexer.example/get/M.C3.BCnchen</link></item>
                </channel></rss>
            """.trimIndent())
        })
        try {
            val releases = NewznabClient(http).browse("https://indexer.example", "test-key", "5060")
            assertEquals("Bayern.München.vs.Fenerbahçe.1080p", releases[0].title)
            assertEquals("match%2B123", releases[0].guid)
            assertEquals("https://indexer.example/get/Bayern%20M%C3%BCnchen?id=a%2Bb&key=test-key", releases[0].nzbUrl)
            assertEquals("São.Paulo.vs.東京.🏀", releases[1].title)
            assertEquals("Euroleague.24-25.Bayern.München.vs.Maccabi", releases[2].title)
            assertEquals("raw-C3-BC-id", releases[2].guid)
            assertEquals("https://indexer.example/get/M.C3.BCnchen", releases[2].nzbUrl)
        } finally { http.close() }
    }

    @Test
    fun categoryBrowsingUsesGeneralSearchInsteadOfMovieSearch() = runTest {
        var operation: String? = null
        val http = HttpClient(MockEngine { request ->
            operation = request.url.parameters["t"]
            respond("<rss><channel/></rss>")
        })
        try {
            NewznabClient(http).browse("https://indexer.example", "test-key", "5060,5160")
            assertEquals("search", operation)
        } finally { http.close() }
    }

    @Test
    fun providerPageCapDoesNotSkipResultsOrStopBeforeTheRequestedLimit() = runTest {
        val offsets = mutableListOf<Int>()
        val http = HttpClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            offsets += offset
            val count = minOf(50, request.url.parameters["limit"]!!.toInt(), 150 - offset)
            val items = (offset until offset + count).joinToString("") { index ->
                "<item><title>NBA.$index</title><guid>event-$index</guid><link>https://indexer.example/$index</link></item>"
            }
            respond("<rss><channel>$items</channel></rss>")
        })
        try {
            val results = NewznabClient(http).browseAllPages("https://indexer.example", "test-key", "5060", 150)
            assertEquals(listOf(0, 50, 100), offsets)
            assertEquals((0 until 150).map { "event-$it" }.toSet(), results.map { it.guid }.toSet())
        } finally { http.close() }
    }

    @Test
    fun ignoredOffsetsStopInsteadOfRepeatingTheSamePage() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            respond("<rss><channel><item><title>NBA</title><guid>same</guid><link>https://indexer.example/event</link></item></channel></rss>")
        })
        try {
            val results = NewznabClient(http).browseAllPages("https://indexer.example", "test-key", "5060", 200)
            assertEquals(2, calls)
            assertEquals(1, results.size)
        } finally { http.close() }
    }

    @Test
    fun unavailableProviderIsAnErrorRatherThanAnEmptySportsFeed() = runTest {
        val http = HttpClient(MockEngine { respond("Unavailable", HttpStatusCode.ServiceUnavailable) })
        try {
            assertFailsWith<IllegalStateException> {
                NewznabClient(http).browse("https://indexer.example", "test-key", "5060")
            }
        } finally { http.close() }
    }

    @Test
    fun cancelledSearchDoesNotPublishAnEmptyResult() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("Test cancellation") })
        try {
            assertFailsWith<CancellationException> {
                NewznabClient(http).browse("https://indexer.example", "test-key", "5060")
            }
        } finally { http.close() }
    }

    @Test
    fun boundedSportsRefreshPassesMaxAgeToProvider() = runTest {
        var requestedMaxAge: String? = null
        val client = HttpClient(
            MockEngine { request ->
                requestedMaxAge = request.url.parameters["maxage"]
                respond("<rss><channel></channel></rss>", HttpStatusCode.OK)
            },
        )

        try {
            NewznabClient(client).browseAllPages(
                baseUrl = "https://indexer.example",
                apiKey = "test-key",
                category = "5060",
                maxItems = 20,
                maxAgeDays = 1,
            )
            assertEquals("1", requestedMaxAge)
        } finally {
            client.close()
        }
    }

    @Test
    fun paginationStopsAfterTheProviderReturnsAnEmptyPage() = runTest {
        var requestCount = 0
        val client = HttpClient(
            MockEngine {
                requestCount++
                respond("<rss><channel></channel></rss>", HttpStatusCode.OK)
            },
        )

        try {
            val results = NewznabClient(client).browseAllPages(
                baseUrl = "https://indexer.example",
                apiKey = "test-key",
                category = "5060",
                maxItems = 2_000,
                maxAgeDays = 14,
            )

            assertEquals(emptyList(), results)
            assertEquals(1, requestCount)
        } finally {
            client.close()
        }
    }
}
