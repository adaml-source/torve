package com.torve.data.addon

import com.torve.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class ExtensionHomeCataloguesTest {
    @BeforeTest fun enableExtensionOnly() { ExtensionRuntimePolicy.userMetadataOnly = true }
    @AfterTest fun restorePolicy() { ExtensionRuntimePolicy.userMetadataOnly = false }
    private val config = HomeSectionConfig(HomeSection.ADDON_SHELVES, true, 17)
    private fun source(path: String = "de", count: Int = 18) = InstalledAddon(
        "https://source.test/$path/manifest.json",
        AddonManifest("provider", "Provider", "1", catalogs = (0 until count).map {
            AddonCatalog(if (it % 2 == 0) "movie" else "series", "list-$it", "List $it", extra = listOf(AddonExtra("skip")))
        }))

    @Test fun allSelectedRowsSurviveBeyondTheOldEightRowLimit() {
        val addon = source()
        val extra = listOf(AddonCatalog("movie", "search", extra = listOf(AddonExtra("search", true))),
            AddonCatalog("series", "requires-genre", extra = listOf(AddonExtra("genre", true))))
        val plan = homeCatalogPlan(listOf(addon.copy(manifest = addon.manifest.copy(catalogs = addon.manifest.catalogs + extra))), config, emptyMap())
        assertEquals(18, plan.size)
        assertEquals(addon.manifest.catalogs.map { it.id }.toSet(), plan.map { it.catalog.id }.toSet())
        assertTrue(homeCatalogPlan(listOf(addon), config.copy(enabled = false), emptyMap()).isEmpty())
    }

    @Test fun configuredLanguagesHaveDistinctKeysAndLegacyHiddenSettingsStillApply() {
        val de = source("de"); val en = source("en")
        val plan = homeCatalogPlan(listOf(de, en), config.copy(catalogueRows = mapOf(
            "provider-list-2" to HomeCatalogueRowConfig(enabled = false))), emptyMap())
        assertEquals(34, plan.size)
        assertEquals(34, plan.map { it.shelfId }.toSet().size)
        assertTrue(plan.none { it.catalog.id == "list-2" })
        assertTrue(plan.all { it.shelfId.startsWith("provider-") && "https" !in it.shelfId })
        val last = en.catalogueShelfId(en.manifest.catalogs.last())
        assertEquals(last, homeCatalogPlan(listOf(de, en), config, emptyMap(), listOf("addon:$last")).first().shelfId)
    }

    @Test fun legacyTypeSpecificVisibilityDoesNotHideTheOtherMediaType() {
        val original = source(count = 0)
        val addon = original.copy(manifest = original.manifest.copy(catalogs = listOf(
            AddonCatalog("movie", "popular"), AddonCatalog("series", "popular"))))
        val plan = homeCatalogPlan(listOf(addon), config, mapOf("provider-popular::type=series" to false))
        assertEquals(listOf("movie"), plan.map { it.catalog.type })
    }

    @Test fun everySelectedRowLoadsWithBoundedRequestsAndProgressivePublication() = runTest {
        val lock = Mutex(); var calls = 0; var active = 0; var peak = 0
        val snapshots = mutableListOf<List<String>>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("source.test", request.url.host)
            lock.withLock { calls++; active++; peak = maxOf(peak, active) }
            try { delay(5); respond("""{"metas":[${(1..30).joinToString(",") { """{"id":"tt$it","name":"Title $it"}""" }}]}""") }
            finally { lock.withLock { active-- } }
        })
        try {
            val plan = homeCatalogPlan(listOf(source()), config, emptyMap())
            val rows = CatalogAggregator(StremioAddonClient(http, Json)).fetchHomeCatalogs(plan) { ready ->
                snapshots += ready.map { it.id }
            }
            assertEquals(18, calls); assertEquals(18, rows.size); assertTrue(peak in 1..2)
            assertEquals((1..18).toList(), snapshots.map { it.size })
            assertTrue(rows.all { it.items.size == 20 })
            assertEquals(plan.map { it.shelfId }, rows.map { it.id })
        } finally { http.close() }
    }

    @Test fun slowOrFailedRowsDoNotHoldBackOtherCatalogues() = runTest {
        val slow = CompletableDeferred<Unit>(); val fast = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { request ->
            when {
                "list-0" in request.url.encodedPath -> { slow.await(); respond("""{"metas":[{"id":"slow","name":"Slow"}]}""") }
                "list-1" in request.url.encodedPath -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                else -> respond("""{"metas":[{"id":"ready","name":"Ready"}]}""")
            }
        })
        try {
            val plan = homeCatalogPlan(listOf(source(count = 3)), config, emptyMap())
            val task = async { CatalogAggregator(StremioAddonClient(http, Json)).fetchHomeCatalogs(plan) {
                if (it.any { row -> row.items.any { media -> media.id == "ready" } }) fast.complete(Unit)
            } }
            withContext(Dispatchers.Default) { withTimeout(3_000) { fast.await() } }
            assertFalse(task.isCompleted)
            slow.complete(Unit)
            assertEquals(2, task.await().size)
        } finally { slow.complete(Unit); http.close() }
    }

    @Test fun seeAllUsesTheSelectedLanguageInstanceAfterBothAreInstalled() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request -> paths += request.url.encodedPath
            respond("""{"metas":[{"id":"tt1234567","name":"Next"}]}""") })
        try {
            val de = source("de", 2); val en = source("en", 2)
            val shelf = en.catalogueShelfId(en.manifest.catalogs.last())
            val result = CatalogAggregator(StremioAddonClient(http, Json)).shelfPage(listOf(de, en), shelf, 20)
            assertEquals(listOf("/en/catalog/series/list-1/skip=20.json"), paths)
            assertEquals(MediaType.SERIES, result.items.single().type)
        } finally { http.close() }
    }
}
