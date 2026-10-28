package com.torve.data.addon

import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.test.*

class HomeCatalogPlanTest {
    private val config = HomeSectionConfig(HomeSection.ADDON_SHELVES, true, 1)
    private fun addon(id: String, count: Int) = InstalledAddon("https://$id.test/manifest.json",
        AddonManifest(id, id, "1", catalogs = (0 until count).map { AddonCatalog(if (it % 2 == 0) "movie" else "series", "list-$it") }))

    @Test fun largeExtensionsGetOneSmallFairHomeBatch() {
        val plan = homeCatalogPlan(listOf(addon("catalogs", 25), addon("aio", 69)), config, emptyMap())
        assertEquals(8, plan.size)
        assertEquals(setOf("catalogs", "aio"), plan.map { it.addon.manifest.id }.toSet())
        assertEquals(setOf("movie", "series"), plan.map { it.catalog.type }.toSet())
        assertEquals(4, plan.count { it.addon.manifest.id == "aio" })
    }
    @Test fun disabledSectionDoesNoCatalogueWork() {
        assertTrue(homeCatalogPlan(listOf(addon("aio", 69)), config.copy(enabled = false), emptyMap()).isEmpty())
    }
    @Test fun hiddenSearchAndRemovedCataloguesAreExcludedBeforeFetching() {
        val addon = addon("aio", 12).let { it.copy(manifest = it.manifest.copy(catalogs = it.manifest.catalogs +
            AddonCatalog("movie", "search", extra = listOf(AddonExtra("search", true))))) }
        val plan = homeCatalogPlan(listOf(addon), config.copy(catalogueRows = mapOf("aio-list-2" to HomeCatalogueRowConfig(enabled = false))),
            mapOf("aio-list-0" to false))
        assertTrue(plan.none { it.catalog.id in setOf("list-0", "list-2", "search") })
    }
    @Test fun removedAndDisabledExtensionsCannotReturnThroughCachedShelfIds() {
        val old = homeCatalogPlan(listOf(addon("removed", 25), addon("kept", 69)), config, emptyMap())
        val current = homeCatalogPlan(listOf(addon("kept", 69), addon("disabled", 20).copy(isEnabled = false)), config, emptyMap()).map { it.shelfId }.toSet()
        assertTrue(old.filter { it.shelfId in current }.all { it.addon.manifest.id == "kept" })
        assertTrue(current.none { it.startsWith("removed-") || it.startsWith("disabled-") })
    }
    @Test fun savedHomeOrderCanSelectLaterCatalogueWithoutFetchingEarlierOnes() {
        val plan = homeCatalogPlan(listOf(addon("aio", 69)), config, emptyMap(), listOf("addon:aio-list-68"))
        assertEquals("aio-list-68", plan.first().shelfId)
        assertEquals(8, plan.size)
    }
    @Test fun homeNetworkBatchCapsRequestsItemsAndConcurrency() = runTest {
        val lock = Mutex();var calls = 0;var active = 0;var peak = 0
        val http = HttpClient(MockEngine {
            lock.withLock { calls++; active++; peak = maxOf(peak, active) }
            try { delay(10); respond("""{"metas":[${(0..99).joinToString(",") { """{"id":"$it","name":"Item $it","type":"movie"}""" }}]}""") }
            finally { lock.withLock { active-- } }
        })
        try {
            val plan = homeCatalogPlan(listOf(addon("aio", 69)), config, emptyMap())
            val shelves = CatalogAggregator(StremioAddonClient(http, Json)).fetchHomeCatalogs(plan)
            assertEquals(8, calls); assertEquals(8, shelves.size)
            assertTrue(peak in 1..2); assertTrue(shelves.all { it.items.size == 20 })
        } finally { http.close() }
    }
    @Test fun cancellingHomeCancelsActiveAndQueuedRequests() = runTest {
        val started = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { started.complete(Unit); delay(60_000); respond("""{"metas":[]}""") })
        try {
            val task = async { CatalogAggregator(StremioAddonClient(http, Json)).fetchHomeCatalogs(homeCatalogPlan(listOf(addon("aio", 69)), config, emptyMap())) }
            started.await();task.cancelAndJoin();assertTrue(task.isCancelled)
        } finally { http.close() }
    }
}
