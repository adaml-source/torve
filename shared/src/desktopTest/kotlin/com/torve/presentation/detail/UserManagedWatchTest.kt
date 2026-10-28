package com.torve.presentation.detail

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.addon.*
import com.torve.data.kodi.KodiClient
import com.torve.data.mdblist.*
import com.torve.data.metadata.TmdbApiClient
import com.torve.data.ratings.OmdbClient
import com.torve.data.simkl.SimklClient
import com.torve.data.trakt.TraktClient
import com.torve.data.trakt.api.TraktAuthorizedApi
import com.torve.data.trakt.auth.TraktTokenStore
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.*
import com.torve.domain.model.*
import com.torve.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.*

/** Exercises the actual detail Watch path, without metadata services or real provider credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
class UserManagedWatchTest {
    private inline fun <reified T> stub(noinline call: (String, Array<out Any?>) -> Any? = { name, _ -> error("Unexpected ${T::class.simpleName}.$name") }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private val ready = ParsedStream("Fixture", "1080p", "Ready H264", directUrl = "https://video.example/ready.mp4", codec = "H264")
    private val movie = MediaItem("fixture:film", type = MediaType.MOVIE, title = "Fixture film")

    private class Sources : StreamRepository by (Proxy.newProxyInstance(StreamRepository::class.java.classLoader,
        arrayOf(StreamRepository::class.java)) { _, method, _ -> error("Unexpected stream call ${method.name}") } as StreamRepository) {
        var results = emptyList<ParsedStream>()
        var searchDelay = 0L
        var partialResult: StreamFetchResult? = null
        var attempts = mutableListOf<String>()
        var searchedId: String? = null
        var searchedEpisode: Pair<Int?, Int?>? = null
        var failFirst = false
        var readiness: suspend (String) -> StreamReadiness = { StreamReadiness.Ready(it) }
        val probes = mutableListOf<String>()
        override suspend fun fetchStreamsProgressively(type: MediaType, imdbId: String, contentId: String?, title: String?,
            season: Int?, episode: Int?, addons: List<InstalledAddon>, debridAccounts: Map<DebridServiceType, String>,
            preferences: StreamPreferences, fetchPolicy: StreamFetchPolicy,
            onPartialResults: suspend (StreamFetchResult) -> Unit): StreamFetchResult {
            partialResult?.let { onPartialResults(it) }
            return fetchStreamsWithFeedback(type, imdbId, contentId, title, season, episode, addons, debridAccounts, preferences, fetchPolicy)
        }
        override suspend fun fetchStreamsWithFeedback(type: MediaType, imdbId: String, contentId: String?, title: String?,
            season: Int?, episode: Int?, addons: List<InstalledAddon>, debridAccounts: Map<DebridServiceType, String>,
            preferences: StreamPreferences, fetchPolicy: StreamFetchPolicy): StreamFetchResult {
            assertTrue(debridAccounts.isEmpty())
            assertEquals(StreamFetchPolicy.FULL, fetchPolicy)
            searchedId = imdbId; searchedEpisode = season to episode
            delay(searchDelay)
            return StreamFetchResult(results)
        }
        override suspend fun resolveStream(stream: ParsedStream, provider: DebridServiceType?, apiKey: String): ResolvedStream {
            attempts += stream.directUrl!!
            if (failFirst && attempts.size == 1) error("Fixture source offline")
            return ResolvedStream(stream.directUrl)
        }
        override suspend fun reportPlaybackOutcome(stream: ParsedStream, provider: DebridServiceType?, success: Boolean) = Unit
        override suspend fun probeStreamReadiness(url: String): StreamReadiness {
            probes += url
            return readiness(url)
        }
    }

    private suspend fun TestScope.withDetail(block: suspend TestScope.(DetailViewModel, Sources) -> Unit) {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val http = HttpClient(MockEngine { error("Unexpected external HTTP request") })
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        val preferences = stub<PreferencesRepository> { _, _ -> null }
        val secrets = stub<IntegrationSecretStore> { _, _ -> null }
        val trakt = TraktClient(http, Json)
        val sources = Sources()
        val viewModel = DetailViewModel(stub(), sources,
            stub<WatchProgressRepository> { name, _ -> if (name == "getAllProgress") emptyList<WatchProgress>() else null },
            TraktAuthorizedApi(trakt, TraktTokenStore(secrets, Json)), stub(),
            stub<AddonRepository> { _, _ -> emptyList<InstalledAddon>() },
            stub<WatchHistoryRepository> { name, _ -> when (name) {
                "observeForMedia" -> flowOf(emptyList<WatchHistoryEntry>())
                "getForMedia" -> emptyList<WatchHistoryEntry>()
                "refreshSeries" -> Unit
                else -> error("Unexpected history $name")
            } }, stub(), preferences, stub(), StreamSelector(StreamScorer()),
            RatingsEnricher(MdbListApi(http), TmdbApiClient(http), trakt, RatingsCacheRepository(TorveDatabase(driver)),
                OmdbClient(http, preferences, secrets)), secrets, KodiClient(http), SimklClient(http))
        try { block(viewModel, sources) }
        finally { viewModel.clear(); http.close(); driver.close(); Dispatchers.resetMain(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun watchSearchesAndAutomaticallyResolvesWithoutOpeningPicker() = runTest { withDetail { vm, sources ->
        sources.results = listOf(ready)
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); advanceUntilIdle()
        assertEquals(movie.id, sources.searchedId)
        assertEquals(ready.directUrl, vm.state.value.resolvedStream?.url)
        assertFalse(vm.state.value.showStreamPicker)
        assertFalse(vm.state.value.isLoadingStreams)
    } }

    @Test fun chooseSourceNeverAutomaticallyResolves() = runTest { withDetail { vm, sources ->
        sources.results = listOf(ready)
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(forceManualPick = true); advanceUntilIdle()
        assertTrue(sources.attempts.isEmpty())
        assertTrue(vm.state.value.showStreamPicker)
        assertEquals(1, vm.state.value.streams.size)
    } }

    @Test fun failedAutomaticSourceFallsBackToAnotherCandidate() = runTest { withDetail { vm, sources ->
        sources.failFirst = true
        sources.results = listOf(ready, ready.copy(directUrl = "https://video.example/fallback.mp4"))
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); advanceUntilIdle()
        assertEquals(2, sources.attempts.size)
        assertEquals(sources.attempts.last(), vm.state.value.resolvedStream?.url)
    } }

    @Test fun stalledSearchStopsWithActionableError() = runTest { withDetail { vm, sources ->
        sources.searchDelay = 100_000
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); advanceUntilIdle()
        assertEquals(35_000, testScheduler.currentTime)
        assertFalse(vm.state.value.isLoadingStreams)
        assertTrue(vm.state.value.showStreamPicker)
        assertEquals("error_streams_search_timeout", vm.state.value.streamsError)
        assertTrue(sources.attempts.isEmpty())
    } }

    @Test fun searchDeadlineKeepsAvailableSourcesInTheManualPicker() = runTest { withDetail { vm, sources ->
        sources.partialResult = StreamFetchResult(listOf(ready))
        sources.searchDelay = 100_000
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(forceManualPick = true); advanceUntilIdle()
        assertEquals(35_000, testScheduler.currentTime)
        assertEquals(listOf(ready), vm.state.value.streams)
        assertNull(vm.state.value.streamsError)
        assertFalse(vm.state.value.isLoadingStreams)
        assertFalse(vm.state.value.isLoadingMoreSources)
        assertTrue(vm.state.value.showStreamPicker)
        assertTrue(sources.attempts.isEmpty())
    } }

    @Test fun watchCanUseAnAvailableSourceWhenAnotherProviderNeverFinishes() = runTest { withDetail { vm, sources ->
        sources.partialResult = StreamFetchResult(listOf(ready))
        sources.searchDelay = 100_000
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); advanceUntilIdle()
        assertEquals(35_000, testScheduler.currentTime)
        assertEquals(ready.directUrl, vm.state.value.resolvedStream?.url)
        assertNull(vm.state.value.streamsError)
        assertFalse(vm.state.value.showStreamPicker)
    } }

    @Test fun emptySourceSearchReturnsAnActionableMessageKey() = runTest { withDetail { vm, _ ->
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); advanceUntilIdle()
        assertEquals("error_streams_none_found", vm.state.value.streamsError)
        assertTrue(vm.state.value.showStreamPicker)
        assertFalse(vm.state.value.isLoadingStreams)
    } }

    @Test fun cancelWatchCannotLaunchLateSource() = runTest { withDetail { vm, sources ->
        sources.searchDelay = 20_000; sources.results = listOf(ready)
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); runCurrent(); vm.cancelSourceLookup(); advanceUntilIdle()
        assertNull(vm.state.value.resolvedStream)
        assertFalse(vm.state.value.isLoadingStreams)
        assertTrue(sources.attempts.isEmpty())
    } }

    @Test fun extensionSeriesUsesItsEpisodeMetadataWithoutTmdb() = runTest { withDetail { vm, sources ->
        sources.results = listOf(ready)
        val series = movie.copy(type = MediaType.SERIES, seasons = listOf(Season(2, 3, episodes = listOf(Episode(1), Episode(2), Episode(3)))))
        vm.loadExtensionDetail(Json.encodeToString(series)); runCurrent()
        vm.playNextEpisode(); advanceUntilIdle()
        assertEquals(2 to 1, sources.searchedEpisode)
        assertEquals(ready.directUrl, vm.state.value.resolvedStream?.url)
    } }

    @Test fun pendingAddonDownloadResolvesOnceAndStartsTheReturnedVideoWhenReady() = runTest { withDetail { vm, sources ->
        val selected = ready.copy(addonBaseUrl = "https://video.example")
        val media = "https://cdn.example/ready.mkv"
        sources.results = listOf(selected)
        sources.readiness = { if (sources.probes.size == 1) StreamReadiness.Preparing else StreamReadiness.Ready(media) }
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); runCurrent()
        assertNotNull(vm.state.value.preparing)
        assertNull(vm.state.value.resolvedStream)
        advanceUntilIdle()
        assertEquals(listOf(selected.directUrl), sources.attempts)
        assertEquals(listOf(selected.directUrl, selected.directUrl), sources.probes)
        assertEquals(media, vm.state.value.resolvedStream?.url)
        assertNull(vm.state.value.preparing)
        assertNull(vm.state.value.resolveError)
    } }

    @Test fun preparationTimeoutIncludesTimeSpentWaitingForTheProvider() = runTest { withDetail { vm, sources ->
        sources.results = listOf(ready.copy(addonBaseUrl = "https://video.example"))
        sources.readiness = {
            if (sources.probes.size == 1) StreamReadiness.Preparing else {
                delay(400_000)
                StreamReadiness.Ready("https://cdn.example/too-late.mkv")
            }
        }
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); advanceUntilIdle()
        assertEquals(300_000, testScheduler.currentTime)
        assertNull(vm.state.value.resolvedStream)
        assertNull(vm.state.value.preparing)
        assertTrue(vm.state.value.showStreamPicker)
        assertEquals("error_stream_preparing_timeout", vm.state.value.resolveError)
        assertEquals(1, sources.attempts.size)
    } }

    @Test fun cancellingPreparationCannotStartTheLateProviderResponse() = runTest { withDetail { vm, sources ->
        sources.results = listOf(ready.copy(addonBaseUrl = "https://video.example"))
        sources.readiness = {
            if (sources.probes.size == 1) StreamReadiness.Preparing else {
                delay(60_000)
                StreamReadiness.Ready("https://cdn.example/cancelled.mkv")
            }
        }
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertEquals(2, sources.probes.size)
        vm.cancelPreparing(); advanceUntilIdle()
        assertNull(vm.state.value.resolvedStream)
        assertNull(vm.state.value.preparing)
        assertNull(vm.state.value.resolveError)
        assertTrue(vm.state.value.showStreamPicker)
    } }

    @Test fun slowInitialProviderResponseIsAllowedToReachReady() = runTest { withDetail { vm, sources ->
        sources.results = listOf(ready.copy(addonBaseUrl = "https://video.example"))
        sources.readiness = {
            delay(60_000)
            StreamReadiness.Ready("https://cdn.example/slow-ready.mkv")
        }
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.fetchStreams(); advanceUntilIdle()
        assertEquals(60_000, testScheduler.currentTime)
        assertEquals("https://cdn.example/slow-ready.mkv", vm.state.value.resolvedStream?.url)
        assertEquals(1, sources.attempts.size)
        assertFalse(vm.state.value.showStreamPicker)
    } }

    @Test fun manuallySelectedPendingSourceAlsoWaitsWithoutResubmittingResolution() = runTest { withDetail { vm, sources ->
        val selected = ready.copy(addonBaseUrl = "https://video.example")
        sources.readiness = { if (sources.probes.size == 1) StreamReadiness.Preparing
            else StreamReadiness.Ready("https://cdn.example/manual-ready.mkv") }
        vm.loadExtensionDetail(Json.encodeToString(movie))
        vm.resolveStream(selected, null, ""); runCurrent()
        assertNotNull(vm.state.value.preparing)
        advanceUntilIdle()
        assertEquals(listOf(selected.directUrl), sources.attempts)
        assertEquals("https://cdn.example/manual-ready.mkv", vm.state.value.resolvedStream?.url)
        assertNull(vm.state.value.preparing)
    } }
}
