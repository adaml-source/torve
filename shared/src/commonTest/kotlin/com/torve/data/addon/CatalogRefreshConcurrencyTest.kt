package com.torve.data.addon

import com.torve.domain.model.AddonCatalog
import com.torve.domain.model.AddonManifest
import com.torve.domain.model.InstalledAddon
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogRefreshConcurrencyTest {
    private fun addon(count: Int) = InstalledAddon("https://catalogue.test/manifest.json",
        AddonManifest("catalogue.test", "Catalogue", "1", catalogs =
            (0 until count).map { AddonCatalog("movie", "list-$it") }))

    @Test fun largeCatalogueRefreshBoundsRequestsAndStillReturnsEveryShelf() = runTest {
        val mutex = Mutex()
        var active = 0
        var peak = 0
        val http = HttpClient(MockEngine { request ->
            mutex.withLock { active++; peak = maxOf(peak, active) }
            try {
                delay(25)
                val id = request.url.encodedPath.substringAfterLast('/').removeSuffix(".json")
                respond("""{"metas":[{"id":"$id","type":"movie","name":"$id"}]}""")
            } finally { mutex.withLock { active-- } }
        })
        try {
            val shelves = CatalogAggregator(StremioAddonClient(http, Json { ignoreUnknownKeys = true }))
                .fetchCatalogs(listOf(addon(84)))
            assertEquals(84, shelves.size)
            assertEquals(84, shelves.map { it.id }.toSet().size)
            assertTrue(peak in 1..4, "Concurrent requests: $peak")
        } finally { http.close() }
    }

    @Test fun aFailedCatalogueDoesNotDiscardOtherReadyShelves() = runTest {
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("list-0")) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond("""{"metas":[{"id":"ready","type":"movie","name":"Ready"}]}""")
        })
        try {
            val shelves = CatalogAggregator(StremioAddonClient(http, Json)).fetchCatalogs(listOf(addon(2)))
            assertEquals(listOf("catalogue.test-list-1"), shelves.map { it.id })
        } finally { http.close() }
    }

    @Test fun leavingThePageCancelsQueuedAndRunningCatalogueRequests() = runTest {
        val started = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            started.complete(Unit)
            delay(60_000)
            respond("""{"metas":[]}""")
        })
        try {
            val request = async { CatalogAggregator(StremioAddonClient(http, Json)).fetchCatalogs(listOf(addon(84))) }
            started.await()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        } finally { http.close() }
    }
}
