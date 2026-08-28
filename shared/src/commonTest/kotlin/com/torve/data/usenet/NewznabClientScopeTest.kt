package com.torve.data.usenet

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NewznabClientScopeTest {
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
}

