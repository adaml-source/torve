package com.torve.data.replays

import com.torve.domain.model.ContentPolicyState
import com.torve.domain.repository.PreferencesRepository
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WebReplayCatalogueTest {
    @Test fun repeatedSettingsRefreshesDoNotCancelTheWebsiteAlreadyLoading() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val f = Fixture(this) { calls++; gate.await(); respond(card()) }; f.config()
        try {
            runCurrent()
            repeat(10) { f.store.refresh(); runCurrent() }
            assertEquals(1, calls)
            gate.complete(Unit); runCurrent()
            assertEquals(1, calls)
            assertEquals(1, f.store.state.value.events.size)
            assertFalse(f.store.state.value.loading)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun backendConfigurationChangeIsAppliedAfterTheInFlightRefresh() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val f = Fixture(this) { calls++; gate.await(); respond(card()) }; f.config()
        try {
            runCurrent(); f.config(enabled = false); f.store.refresh(); runCurrent()
            assertEquals(1, calls)
            gate.complete(Unit); runCurrent()
            assertFalse(f.store.state.value.enabled)
            assertEquals(1, calls)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun fallbackNeverStartsAnotherGameOnAMultiGamePage() = runTest {
        val f = Fixture(this) { request -> if (request.url.host == "broken.example") respond("browser needed", HttpStatusCode.Forbidden) else respond(card()) }; f.config()
        try {
            runCurrent(); val event = f.store.state.value.events.single()
            val paris = ReplaySourceOption("paris", "Source", "https://broken.example/paris", event.pageUrl, "Paris vs Valencia")
            val other = ReplaySourceOption("other", "Source", "https://cdn.example/olympiacos.m3u8", event.pageUrl, "Olympiacos vs Fenerbahce")
            assertNull(f.store.resolveAvailable(event, paris, listOf(paris, other)))
        } finally { f.close() }
    }
    @Test fun accountRefreshReloadsConfigurationForTheAlreadyLoadedProfile() = runTest {
        val f = Fixture(this) { respond(card()) }; f.config(enabled = false)
        try {
            runCurrent(); assertFalse(f.store.state.value.enabled)
            f.config(enabled = true); f.store.refresh(); runCurrent()
            assertTrue(f.store.state.value.enabled); assertEquals(1, f.store.state.value.events.size)
        } finally { f.close() }
    }
    private val site = ReplayWebsite("one", "Sports", "https://sports.example/")
    private fun card(title: String = "NBA replay", path: String = "/game") = """<article><h2><a href="$path">$title</a></h2><img src="/art.jpg"></article>"""
    private inner class Fixture(scope: TestScope, policy: ContentPolicyState = ContentPolicyState.unrestricted(), handler: MockRequestHandler) {
        var owner = "a"
        val storage = mutableMapOf<String, MutableMap<String, String>>()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val prefs = object : PreferencesRepository {
            override suspend fun getString(key: String) = storage[owner]?.get(key)
            override suspend fun setString(key: String, value: String) { storage.getOrPut(owner) { mutableMapOf() }[key] = value }
            override suspend fun remove(key: String) { storage[owner]?.remove(key) }
        }
        val client = HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = UnconfinedTestDispatcher(scope.testScheduler); addHandler(handler) }))
        val repo = WebReplayRepository(client, UnconfinedTestDispatcher(scope.testScheduler))
        val store = WebReplayCatalogue(prefs, repo, { owner }, changes, policy = { policy }, scope = scope.backgroundScope, now = { 1_000_000 })
        fun config(owner: String = this.owner, enabled: Boolean = true, websites: List<ReplayWebsite> = listOf(site)) {
            storage.getOrPut(owner) { mutableMapOf() }[WebReplayCatalogue.SETTINGS] = Json.encodeToString(ReplayConfiguration(enabled, websites))
        }
        fun cache(event: ReplayEvent) {
            storage.getOrPut(owner) { mutableMapOf() }[WebReplayCatalogue.CACHE] = Json.encodeToString(ReplayCache(listOf(event), 1))
        }
        fun close() { store.close(); client.close() }
    }
    @Test fun disabledFeatureMakesNoNetworkRequests() = runTest {
        var calls = 0; val f = Fixture(this) { calls++; respond(card()) }
        f.config(enabled = false)
        try { runCurrent(); assertTrue(f.store.state.value.loaded); assertFalse(f.store.state.value.enabled); assertEquals(0, calls) }
        finally { f.close() }
    }
    @Test fun savesUserWebsiteBeforeActivationAndUsesTheSameConfiguration() = runTest {
        var calls = 0; val f = Fixture(this) { calls++; respond(card()) }
        try {
            runCurrent(); f.store.addWebsite(site.url, "Sports"); runCurrent(); assertEquals(0, calls)
            f.store.configure(true); runCurrent()
            assertEquals(1, f.store.state.value.websites.size); assertEquals(1, calls)
            assertEquals("NBA replay", f.store.state.value.events.single().title)
            val settings = Json.decodeFromString<ReplayConfiguration>(f.storage.getValue("a").getValue(WebReplayCatalogue.SETTINGS))
            assertTrue(settings.enabled); assertEquals(site.url, settings.websites.single().url)
        } finally { f.close() }
    }
    @Test fun showsCachedPostersWhileRefreshingAndKeepsThemAfterFailure() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this) { gate.await(); respond("blocked", HttpStatusCode.Forbidden) }
        f.config(); f.cache(ReplayEvent("old", site.id, "Cached game", "https://sports.example/old", "https://sports.example/old.jpg"))
        try {
            runCurrent(); assertTrue(f.store.state.value.loading); assertEquals("Cached game", f.store.state.value.events.single().title)
            gate.complete(Unit); runCurrent()
            assertFalse(f.store.state.value.loading); assertEquals("Cached game", f.store.state.value.events.single().title)
            assertTrue(f.store.state.value.error.orEmpty().contains("browser verification"))
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun aLateResponseCannotCrossAnOwnerBoundary() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this) { request ->
            if (request.url.host == "sports.example") { withContext(NonCancellable) { gate.await() }; respond(card("Private A")) }
            else respond(card("Private B"))
        }
        f.config(); f.config("b", websites = listOf(site.copy(id = "two", url = "https://second.example/")))
        try {
            runCurrent(); f.owner = "b"; f.changes.tryEmit(Unit); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals("b", f.store.state.value.owner)
            assertEquals(listOf("Private B"), f.store.state.value.events.map { it.title })
            assertFalse(f.storage.getValue("b").values.any { "Private A" in it })
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun disablingAWebsiteRejectsItsLateResponse() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this) { withContext(NonCancellable) { gate.await() }; respond(card()) }
        f.config()
        try { runCurrent(); f.store.setWebsiteEnabled(site.id, false); runCurrent(); gate.complete(Unit); runCurrent()
            assertTrue(f.store.state.value.events.isEmpty()); assertFalse(f.store.state.value.websites.single().enabled)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun paginationDeduplicatesAndStopsRepeatingPages() = runTest {
        var calls = 0; val f = Fixture(this) { calls++; respond(card() + """<a rel="next" href="?page=2">Next</a>""") }
        f.config()
        try { runCurrent(); assertTrue(f.store.state.value.hasMore); f.store.loadMore(); runCurrent()
            assertEquals(2, calls); assertEquals(1, f.store.state.value.events.size); assertFalse(f.store.state.value.hasMore)
        } finally { f.close() }
    }
    @Test fun cachedCatalogueDoesNotRefetchOnEveryAppearance() = runTest {
        var calls = 0; val f = Fixture(this) { calls++; respond(card()) }; f.config()
        try { runCurrent(); assertEquals(1, calls); repeat(5) { f.store.refresh(); runCurrent() }; assertEquals(1, calls) }
        finally { f.close() }
    }
    @Test fun signedStreamUrlsNeverEnterSavedReplayMetadata() = runTest {
        val f = Fixture(this) { request -> respond(if (request.url.encodedPath == "/game") """<video src="https://cdn.example/video.mp4?secret=shortlived"></video>""" else card()) }
        f.config()
        try {
            runCurrent(); val event = f.store.state.value.events.single(); val options = f.store.sources(event)
            val stream = f.store.resolve(event, options.single()); assertTrue(stream.url.contains("shortlived"))
            assertFalse(f.storage.getValue("a").values.any { "shortlived" in it })
        } finally { f.close() }
    }
    @Test fun replayResumeRequiresRealOutputAndDoesNotPolluteMovieProgress() {
        val original = mapOf("web-replay:one" to ReplayProgress(30_000, 100_000, 1))
        assertEquals(original, ReplayProgressPolicy.record(original, "web-replay:one", 70_000, 100_000, false, 2))
        assertEquals(original, ReplayProgressPolicy.record(original, "movie:1", 70_000, 100_000, true, 2))
        val updated = ReplayProgressPolicy.record(original, "web-replay:one", 70_000, 100_000, true, 2)
        assertEquals(70_000, ReplayProgressPolicy.start(updated["web-replay:one"]))
        assertEquals(0, ReplayProgressPolicy.start(ReplayProgress(99_000, 100_000, 3)))
    }
    @Test fun replayResumeStorageIsBoundedAndKeepsOtherEvents() {
        val values = (0..210).associate { "web-replay:$it" to ReplayProgress(10, 100, it.toLong()) }
        val saved = ReplayProgressPolicy.record(values, "web-replay:new", 30, 100, true, 1000)
        assertEquals(200, saved.size); assertTrue("web-replay:new" in saved); assertTrue("web-replay:210" in saved)
        assertFalse("web-replay:0" in saved)
    }
    @Test fun publishesFastWebsitesBeforeASlowWebsiteCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this) { request -> if (request.url.host == "second.example") { gate.await(); respond("blocked", HttpStatusCode.Forbidden) } else respond(card()) }
        f.config(websites = listOf(site, site.copy(id = "two", name = "Slow", url = "https://second.example/")))
        try { runCurrent(); assertTrue(f.store.state.value.loading); assertEquals(1, f.store.state.value.events.size)
            gate.complete(Unit); runCurrent(); assertFalse(f.store.state.value.loading); assertEquals(1, f.store.state.value.events.size)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun initialObserversDoNotRestartAnIdenticalInFlightLoad() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val f = Fixture(this) { calls++; gate.await(); respond(card()) }; f.config()
        try { runCurrent(); repeat(3) { f.changes.tryEmit(Unit); runCurrent() }; assertEquals(1, calls)
            gate.complete(Unit); runCurrent(); assertEquals(1, f.store.state.value.events.size)
        } finally { gate.complete(Unit); f.close() }
    }
    @Test fun incompatibleHostFallsThroughToWorkingAlternative() = runTest {
        val f = Fixture(this) { request -> if (request.url.host == "broken.example") respond("browser needed", HttpStatusCode.Forbidden) else respond(card()) }; f.config()
        try {
            runCurrent(); val event = f.store.state.value.events.single()
            val bad = ReplaySourceOption("bad", "Interactive host", "https://broken.example/embed/1", event.pageUrl)
            val good = ReplaySourceOption("good", "Direct host", "https://cdn.example/game.m3u8", event.pageUrl)
            assertEquals(good.url, f.store.resolveAvailable(event, bad, listOf(bad, good))?.url)
        } finally { f.close() }
    }
    @Test fun allIncompatibleHostsReturnWebsiteFallback() = runTest {
        val f = Fixture(this) { request -> if (request.url.host == "broken.example") respond("browser needed", HttpStatusCode.Forbidden) else respond(card()) }; f.config()
        try {
            runCurrent(); val event = f.store.state.value.events.single()
            val bad = ReplaySourceOption("bad", "Interactive host", "https://broken.example/embed/1", event.pageUrl)
            assertNull(f.store.resolveAvailable(event, bad, listOf(bad)))
        } finally { f.close() }
    }
    @Test fun unratedWebCardsRespectRestrictedContentPolicy() = runTest {
        val f = Fixture(this, ContentPolicyState.lockedBootstrap(enforcementEnabled = true, isSignedIn = false)) { respond(card()) }; f.config()
        try { runCurrent(); assertTrue(f.store.state.value.events.isEmpty()) } finally { f.close() }
    }
    @Test fun verifiedAdultCanBrowseSavedSportsWithoutEnablingSensitiveMaterial() = runTest {
        val policy = ContentPolicyState(enforcementEnabled = true, isSignedIn = true,
            ageBand = com.torve.domain.model.ContentAgeBand.ADULT, adultEligible = true, sensitiveMaterialEnabled = false)
        val f = Fixture(this, policy) { respond(card()) }; f.config()
        try { runCurrent(); assertEquals("NBA replay", f.store.state.value.events.single().title) } finally { f.close() }
    }
    @Test fun savedSportsStillHideSensitiveTitlesForAnAdultWhoDisabledThem() = runTest {
        val policy = ContentPolicyState(enforcementEnabled = true, isSignedIn = true,
            ageBand = com.torve.domain.model.ContentAgeBand.ADULT, adultEligible = true, sensitiveMaterialEnabled = false)
        val f = Fixture(this, policy) { respond(card("Porn compilation")) }; f.config()
        try { runCurrent(); assertTrue(f.store.state.value.events.isEmpty()) } finally { f.close() }
    }
}
