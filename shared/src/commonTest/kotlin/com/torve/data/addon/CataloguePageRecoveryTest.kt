package com.torve.data.addon

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class CataloguePageRecoveryTest {
    @Test fun pagingAndSearchKeepTheProviderOwnedIdentity() = runTest {
        var requested = ""
        val http = HttpClient(MockEngine { request ->
            requested = request.url.encodedPath
            respond("""{"metas":[{"id":"provider:opaque:42","type":"movie","name":"A title"}]}""")
        })
        try {
            val page = StremioAddonClient(http, Json { ignoreUnknownKeys = true }).fetchCatalog(
                "https://catalogue.example", "movie", "all", skip = 20, search = "spider man", failOnError = true)
            assertEquals("provider:opaque:42", page.metas.single().toMediaItem().id)
            assertTrue(requested.contains("search=spider%20man"))
            assertTrue(requested.contains("skip=20"))
        } finally { http.close() }
    }

    @Test fun browserReceivesHttpFailureInsteadOfAnEmptySuccess() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable) })
        try {
            val client = StremioAddonClient(http, Json)
            val failure = assertFailsWith<IllegalStateException> {
                client.fetchCatalog("https://catalogue.example", "movie", "all", failOnError = true)
            }
            assertTrue(failure.message.orEmpty().contains("503"))
        } finally { http.close() }
    }

    @Test fun invalidResponseIsRecoverableAndLegacyCallersKeepTheirFallback() = runTest {
        val http = HttpClient(MockEngine { respond("<html>Unavailable</html>") })
        try {
            val client = StremioAddonClient(http, Json)
            assertFails { client.fetchCatalog("https://catalogue.example", "movie", "all", failOnError = true) }
            assertTrue(client.fetchCatalog("https://catalogue.example", "movie", "all").metas.isEmpty())
        } finally { http.close() }
    }

    @Test fun cancellingABrowserNeverBecomesANoResultsResponse() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("Screen left") })
        try {
            assertFailsWith<CancellationException> {
                StremioAddonClient(http, Json).fetchCatalog("https://catalogue.example", "movie", "all")
            }
        } finally { http.close() }
    }
}
