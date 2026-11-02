package com.torve.presentation.home

import com.torve.data.addon.*
import com.torve.data.metadata.MetadataRepositoryImpl
import com.torve.data.metadata.TmdbApiClient
import com.torve.domain.model.*
import com.torve.domain.repository.MetadataRepository
import com.torve.presentation.contentpolicy.ContentPolicyFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NowStreamingCatalogueTest {
    private val dispatcher = StandardTestDispatcher()
    private val requests = mutableListOf<Url>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var owner = "first"
    private var configuration = NowStreamingConfiguration(owner, "DE", "en", setOf(8, 9), true, null, ContentPolicyState.unrestricted())
    private var fail = false
    private var blockedProvider: String? = null
    private var gate = CompletableDeferred<Unit>()
    private var lastPages = mapOf("movie" to 2, "tv" to 2)
    private var sensitive = false
    private var unknown = false
    private var discoveryCalls = 0
    private var itemsPerPage = 1
    private var time = 1_000L
    private var hydrate: (List<MediaItem>) -> List<MediaItem> = { it }
    private var enrich: suspend (List<MediaItem>) -> List<MediaItem> = { it }
    private var extensionFetch: suspend (StreamingCatalogueSource, Int) -> AddonShelfPage = { _, _ -> error("Not configured") }
    private val extensionRequests = mutableListOf<Pair<String, Int>>()
    private val ratingBatches = mutableListOf<List<MediaItem>>()
    private var previousMode = false
    private lateinit var http: HttpClient
    private lateinit var model: NowStreamingCatalogue

    @BeforeTest fun setup() {
        previousMode = ExtensionRuntimePolicy.userMetadataOnly
        ExtensionRuntimePolicy.userMetadataOnly = false
        Dispatchers.setMain(dispatcher)
        http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = this@NowStreamingCatalogueTest.dispatcher
            addHandler { request ->
                val url = request.url
                requests += url
                if (url.encodedPath.contains("/watch/providers/")) {
                    respond("""{"results":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":1796,"provider_name":"Netflix Standard with Ads"},{"provider_id":999,"provider_name":"Netflix Amazon Channel"},{"provider_id":9,"provider_name":"Amazon Prime Video"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    assertTrue(url.encodedPath.contains("/discover/"), "Unscoped discovery must never run")
                    assertNotNull(url.parameters["watch_region"])
                    assertFalse(url.parameters["with_watch_providers"].isNullOrBlank())
                    if (url.parameters["with_watch_providers"] == blockedProvider) withContext(NonCancellable) { gate.await() }
                    val type = url.encodedPath.substringAfterLast('/')
                    val page = url.parameters["page"]?.toInt() ?: 1
                    val adult = "false"
                    val entries = (1..itemsPerPage).joinToString(",") { index ->
                        val id = (page - 1) * itemsPerPage + index
                        """{"id":$id,"title":"Movie $id","name":"Series $id","adult":$adult,"popularity":${if (type == "tv") 90 - page else 60 - page},"poster_path":"/poster.jpg"}"""
                    }
                    val body = """{"page":$page,"total_pages":${lastPages.getValue(type)},"total_results":400,"results":[$entries]}"""
                    respond(if (fail) "{}" else body, status = if (fail) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        })) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val actual = MetadataRepositoryImpl(TmdbApiClient(http, apiKeyProvider = { "test" }, resolvedHostProvider = { listOf("127.0.0.1") }, diagnostics = {}))
        val recording = object : MetadataRepository by actual {
            override suspend fun discover(type: String, page: Int, sortBy: String, withGenres: String?, minRating: Float?,
                year: Int?, yearTo: Int?, runtimeGte: Int?, runtimeLte: Int?, originCountries: String?,
                originalLanguage: String?, certification: String?, certificationGte: String?, certificationLte: String?,
                certificationCountry: String?, withCast: String?, withCrew: String?, withWatchProviders: String?,
                watchRegion: String?, withKeywords: String?, withCompanies: String?): PagedResult {
                discoveryCalls++
                if (fail) error("Availability service unavailable")
                return actual.discover(type, page, sortBy, withGenres, minRating, year, yearTo, runtimeGte, runtimeLte,
                    originCountries, originalLanguage, certification, certificationGte, certificationLte,
                    certificationCountry, withCast, withCrew, withWatchProviders, watchRegion, withKeywords, withCompanies)
                    .let { result -> result.copy(items = result.items.map {
                        if (sensitive) it.copy(adult = true) else if (unknown) it.copy(adult = null) else it
                    }) }
            }
        }
        model = NowStreamingCatalogue(recording,
            configuration = { configuration }, owner = { owner }, policyFilter = ContentPolicyFilter(), changes = changes,
            now = { time }, hydrateRatings = { hydrate(it) }, enrichRatings = { ratingBatches += it; enrich(it) },
            ratingsDispatcher = dispatcher, extensionPage = { source, offset ->
                extensionRequests += source.key to offset
                extensionFetch(source, offset)
            })
    }

    @AfterTest fun cleanup() { gate.complete(Unit); model.close(); http.close(); Dispatchers.resetMain(); ExtensionRuntimePolicy.userMetadataOnly = previousMode }
    // Ktor parses bodies on Default; drain both the virtual scheduler and its real dispatcher.
    private suspend fun TestScope.settle() {
        repeat(200) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(5) }
            if (!model.state.value.loading) { advanceUntilIdle(); return }
        }
        error("Catalogue did not settle: ${model.state.value}")
    }
    private fun queries() = requests.filter { it.encodedPath.contains("/discover/") }

    private fun streamingSources(url: String = "https://installed.example/personal/manifest.json") = streamingCatalogueSources(listOf(
        InstalledAddon(url, AddonManifest("pw.ers.netflix-catalog", "Streaming Catalogs", "1", resources = listOf("catalog"),
            catalogs = listOf("nfx", "amp").flatMap { id -> listOf("movie", "series").map { type -> AddonCatalog(type, id) } }))
    ))
    private fun useExtensions(sources: List<StreamingCatalogueSource> = streamingSources()) {
        ExtensionRuntimePolicy.userMetadataOnly = true
        configuration = configuration.copy(extensionSources = sources)
        extensionFetch = { source, _ ->
            AddonShelfPage((1..3).map { index -> MediaItem(id = "${source.service.id}-$index", title = source.service.name,
                type = MediaType.fromString(source.catalog.type), posterUrl = "https://installed.example/poster.jpg",
                familySourceKey = "extension:${source.addon.manifest.id}") }, 3, false)
        }
    }
    @Test fun installedFeedsMergeBothTypesWithoutNativeMetadataRequests() = runTest(dispatcher) {
        useExtensions(); settle()
        assertTrue(requests.isEmpty())
        assertTrue(model.state.value.extensionBacked)
        assertEquals(setOf(8, 9), model.state.value.availableServices.map { it.id }.toSet())
        assertEquals(12, model.state.value.items.size)
        assertEquals(listOf(MediaType.MOVIE, MediaType.SERIES, MediaType.MOVIE, MediaType.SERIES), model.state.value.items.take(4).map { it.type })
        assertFalse(model.state.value.hasMore)
    }
    @Test fun extensionChipsAndReentryReuseFeedsAndFavouritesChangesRetainThem() = runTest(dispatcher) {
        useExtensions(); settle()
        assertEquals(4, extensionRequests.size)
        model.selectProvider(8); settle()
        assertEquals(6, model.state.value.items.size)
        model.selectProvider(null); model.activate(); settle()
        assertEquals(4, extensionRequests.size)
        configuration = configuration.copy(services = setOf(9)); model.activate(); settle()
        assertEquals(listOf(9), model.state.value.serviceIds)
        assertTrue(model.state.value.items.all { it.id.startsWith("9-") })
        assertEquals(4, extensionRequests.size)
    }
    @Test fun extensionPublishesReadyFeedsWhileAnotherIsPending() = runTest(dispatcher) {
        useExtensions()
        extensionFetch = { source, _ ->
            if (source.catalog.type == "movie") gate.await()
            AddonShelfPage(listOf(MediaItem(id = "${source.service.id}", type = MediaType.fromString(source.catalog.type), title = "Ready")), 1, false)
        }
        runCurrent()
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.items.isNotEmpty())
        gate.complete(Unit); settle()
        assertFalse(model.state.value.loading)
        assertEquals(4, model.state.value.items.size)
    }
    @Test fun removedExtensionClearsCardsAndSelectedProviderWithoutFallback() = runTest(dispatcher) {
        useExtensions(); settle(); model.selectProvider(8); settle()
        configuration = configuration.copy(extensionSources = emptyList(), enabled = false)
        model.activate(); settle()
        assertNull(model.state.value.selectedProviderId)
        assertTrue(model.state.value.items.isEmpty()); assertTrue(model.state.value.availableServices.isEmpty())
        assertTrue(requests.isEmpty()); assertFalse(model.state.value.loading)
    }
    @Test fun configuredInstanceChangeInvalidatesCachedFeeds() = runTest(dispatcher) {
        useExtensions(); settle()
        configuration = configuration.copy(extensionSources = streamingSources("https://installed.example/german/manifest.json"))
        model.activate(); settle()
        assertEquals(8, extensionRequests.size)
        assertTrue(extensionRequests.takeLast(4).all { it.first !in extensionRequests.take(4).map { p -> p.first } })
    }
    @Test fun partialExtensionFailureKeepsReadyPostersAndRetryRecovers() = runTest(dispatcher) {
        useExtensions(); val healthy = extensionFetch
        extensionFetch = { source, offset -> if (source.service.id == 9) error("offline") else healthy(source, offset) }
        settle()
        assertEquals(6, model.state.value.items.size); assertNotNull(model.state.value.error)
        assertFalse(model.state.value.loading)
        extensionFetch = healthy; model.refresh(); settle()
        assertEquals(12, model.state.value.items.size); assertNull(model.state.value.error)
    }
    @Test fun extensionPaginationUsesRawOffsetAndDeduplicatesEachType() = runTest(dispatcher) {
        useExtensions()
        extensionFetch = { source, offset ->
            AddonShelfPage(listOf(MediaItem(id = "${source.service.id}-$offset", type = MediaType.fromString(source.catalog.type), title = "Page")), offset + 25, offset == 0)
        }
        settle(); assertTrue(model.state.value.hasMore)
        model.loadMore(); settle()
        assertEquals(listOf(0, 0, 0, 0, 25, 25, 25, 25), extensionRequests.map { it.second })
        assertEquals(8, model.state.value.items.size); assertFalse(model.state.value.hasMore)
    }
    @Test fun extensionAccountChangesRejectLateResponses() = runTest(dispatcher) {
        useExtensions()
        extensionFetch = { _, _ -> withContext(NonCancellable) { gate.await() }; AddonShelfPage(listOf(MediaItem(id = "old", type = MediaType.MOVIE, title = "Old")), 1, false) }
        runCurrent(); owner = "second"; configuration = configuration.copy(owner = owner, services = emptySet())
        model.activate(); runCurrent(); gate.complete(Unit); settle()
        assertTrue(model.state.value.items.isEmpty()); assertFalse(model.state.value.loading)
    }

    @Test fun allServicesUseRegionalOrFiltersAndCombineMoviesAndShows() = runTest(dispatcher) {
        settle()
        assertEquals(listOf(MediaType.SERIES, MediaType.MOVIE), model.state.value.items.map { it.type }, "State: ${model.state.value}; requests: $requests; nativeCalls: $discoveryCalls; mode: ${ExtensionRuntimePolicy.userMetadataOnly}")
        assertEquals(2, queries().size)
        queries().forEach {
            assertEquals("8|9|1796", it.parameters["with_watch_providers"])
            assertEquals("DE", it.parameters["watch_region"])
            assertEquals("popularity.desc", it.parameters["sort_by"])
            assertEquals("flatrate|free|ads", it.parameters["with_watch_monetization_types"])
        }
        assertTrue(model.state.value.hasMore)
    }

    @Test fun chipChangesAreScopedAndReturningToAllUsesRetainedPosters() = runTest(dispatcher) {
        settle()
        model.selectProvider(8); settle()
        assertEquals("8|1796", queries().last().parameters["with_watch_providers"])
        val count = queries().size
        model.selectProvider(null); settle()
        assertEquals(count, queries().size)
        assertEquals(2, model.state.value.items.size)
        assertFalse(model.state.value.loading)
    }

    @Test fun unknownOrDisabledChipCannotTriggerAnUnscopedQuery() = runTest(dispatcher) {
        settle(); val count = queries().size
        model.selectProvider(531); settle()
        assertEquals(count, queries().size); assertNull(model.state.value.selectedProviderId)
    }

    @Test fun removingSelectedFavouriteReturnsToRemainingServices() = runTest(dispatcher) {
        settle(); model.selectProvider(8); settle()
        configuration = configuration.copy(services = setOf(9))
        changes.emit(Unit); settle()
        assertNull(model.state.value.selectedProviderId)
        assertEquals(listOf(9), model.state.value.serviceIds)
        assertEquals("9", queries().last().parameters["with_watch_providers"])
    }

    @Test fun noFavouritesMakesNoFallbackCatalogueRequest() = runTest(dispatcher) {
        configuration = configuration.copy(services = emptySet())
        settle()
        assertTrue(requests.isEmpty()); assertTrue(model.state.value.items.isEmpty())
        assertTrue(model.state.value.enabled); assertFalse(model.state.value.loading)
    }

    @Test fun disabledTmdbMakesNoProviderOrDiscoveryRequests() = runTest(dispatcher) {
        configuration = configuration.copy(enabled = false)
        settle()
        assertTrue(requests.isEmpty()); assertFalse(model.state.value.enabled)
    }

    @Test fun regionAndLanguageChangesInvalidateAllChipCaches() = runTest(dispatcher) {
        settle(); model.selectProvider(8); settle()
        val count = queries().size
        configuration = configuration.copy(region = "GB", language = "de")
        changes.emit(Unit); settle()
        assertEquals("GB", model.state.value.region)
        assertEquals("GB", queries().last().parameters["watch_region"])
        model.selectProvider(null); settle()
        assertEquals(count + 4, queries().size)
    }

    @Test fun staleProviderResponseCannotReplaceNewSelection() = runTest(dispatcher) {
        settle(); blockedProvider = "8|1796"
        model.selectProvider(8); runCurrent()
        model.selectProvider(9); settle()
        assertEquals(9, model.state.value.selectedProviderId)
        gate.complete(Unit); settle()
        assertEquals(9, model.state.value.selectedProviderId)
        assertFalse(model.state.value.loading)
    }

    @Test fun oldOwnerResponseCannotPublishDuringAccountChange() = runTest(dispatcher) {
        blockedProvider = "8|9|1796"; runCurrent()
        owner = "second"; configuration = configuration.copy(owner = owner, services = emptySet())
        changes.emit(Unit); settle()
        gate.complete(Unit); settle()
        assertTrue(model.state.value.items.isEmpty()); assertTrue(model.state.value.serviceIds.isEmpty())
    }

    @Test fun refreshFailureRetainsVisiblePostersAndOffersRetry() = runTest(dispatcher) {
        settle(); val items = model.state.value.items
        fail = true; model.refresh(); settle()
        assertEquals(items, model.state.value.items); assertNotNull(model.state.value.error)
        assertFalse(model.state.value.loading)
        fail = false; model.refresh(); settle()
        assertNull(model.state.value.error)
    }

    @Test fun failureOnNewServiceNeverRelabelsOldPosters() = runTest(dispatcher) {
        settle(); fail = true; model.selectProvider(9); settle()
        assertTrue(model.state.value.items.isEmpty()); assertEquals(9, model.state.value.selectedProviderId)
        assertNotNull(model.state.value.error)
    }

    @Test fun paginationPreservesTypeIdentityAndStopsAtLastPage() = runTest(dispatcher) {
        settle(); model.loadMore(); settle()
        assertEquals(4, model.state.value.items.size)
        assertEquals(4, model.state.value.items.map { it.type to it.id }.distinct().size)
        assertFalse(model.state.value.hasMore)
        val count = queries().size; model.loadMore(); settle(); assertEquals(count, queries().size)
    }

    @Test fun exhaustedMoviePagesDoNotBlockAdditionalTelevisionPages() = runTest(dispatcher) {
        lastPages = mapOf("movie" to 1, "tv" to 2)
        settle(); model.loadMore(); settle()
        assertEquals(3, queries().size)
        assertEquals("tv", queries().last().encodedPath.substringAfterLast('/'))
        assertEquals(3, model.state.value.items.size)
    }

    @Test fun enablingContentPolicyDropsSensitiveRetainedPosters() = runTest(dispatcher) {
        sensitive = true
        settle(); assertEquals(2, model.state.value.items.size)
        configuration = configuration.copy(policy = ContentPolicyState.lockedBootstrap(true))
        changes.emit(Unit); settle()
        assertTrue(model.state.value.items.isEmpty())
    }

    @Test fun expiredChipCacheRefreshesWithoutBlankingMatchingPosters() = runTest(dispatcher) {
        settle(); model.selectProvider(8); settle()
        model.selectProvider(null); settle()
        val count = discoveryCalls; time += 300_001
        model.selectProvider(8); settle()
        assertEquals(count + 2, discoveryCalls); assertEquals(2, model.state.value.items.size)
    }

    @Test fun promotionalRowHidesSensitiveTitlesEvenWithAdultSettingEnabled() = runTest(dispatcher) {
        sensitive = true
        configuration = configuration.copy(policy = ContentPolicyState(enforcementEnabled = true, isSignedIn = true,
            ageBand = ContentAgeBand.ADULT, adultEligible = true, sensitiveMaterialEnabled = true))
        settle(); assertTrue(model.state.value.items.isEmpty())
    }

    @Test fun unclassifiedPostersAreWithheldWhenPolicyIsEnforced() = runTest(dispatcher) {
        unknown = true
        configuration = configuration.copy(policy = ContentPolicyState.lockedBootstrap(true))
        settle(); assertTrue(model.state.value.items.isEmpty())
    }

    @Test fun retainedCollectionLimitDoesNotOfferUnboundedPaginationAfterChipReturn() = runTest(dispatcher) {
        itemsPerPage = 100; lastPages = mapOf("movie" to 3, "tv" to 3)
        settle(); model.loadMore(); settle()
        assertEquals(400, model.state.value.items.size); assertFalse(model.state.value.hasMore)
        model.selectProvider(8); settle(); model.selectProvider(null); settle()
        assertEquals(400, model.state.value.items.size); assertFalse(model.state.value.hasMore)
        val calls = discoveryCalls; model.loadMore(); settle(); assertEquals(calls, discoveryCalls)
    }

    private fun rated(items: List<MediaItem>, imdb: Float = 8.4f) = items.map {
        it.copy(ratings = MediaRatings(imdbScore = imdb, rottenTomatoesScore = 94))
    }

    @Test fun publishedPostersDoNotWaitForExternalRatingsAndCachedImdbAppearsFirst() = runTest(dispatcher) {
        val ratingsGate = CompletableDeferred<Unit>()
        hydrate = { items -> items.map { it.copy(ratings = MediaRatings(imdbScore = 8.1f)) } }
        enrich = { ratingsGate.await(); rated(it) }
        repeat(100) { runCurrent(); if (!model.state.value.loading && model.state.value.items.isNotEmpty()) return@repeat; withContext(Dispatchers.Default) { delay(5) } }
        runCurrent()
        assertFalse(model.state.value.loading)
        assertEquals(2, model.state.value.items.size)
        assertTrue(model.state.value.items.all { it.ratings?.imdbScore == 8.1f })
        val originals = model.state.value.items
        ratingsGate.complete(Unit); settle()
        assertTrue(model.state.value.items.all { it.ratings?.imdbScore == 8.4f && it.ratings?.rottenTomatoesScore == 94 })
        assertEquals(originals.map { it.type to it.id }, model.state.value.items.map { it.type to it.id })
        assertEquals(originals.map { it.posterUrl }, model.state.value.items.map { it.posterUrl })
    }

    @Test fun completeCachedRatingsNeedNoNetworkAndSurviveServiceRoundTrip() = runTest(dispatcher) {
        hydrate = { rated(it) }
        settle(); model.selectProvider(8); settle(); model.selectProvider(null); settle()
        assertTrue(ratingBatches.isEmpty())
        assertTrue(model.state.value.items.all { it.ratings?.imdbScore == 8.4f })
    }

    @Test fun paginatedRatingsKeepMovieAndShowIdentityAndRespectBatchBudget() = runTest(dispatcher) {
        itemsPerPage = 4
        enrich = { items -> items.map { it.copy(ratings = MediaRatings(imdbScore = if (it.type == MediaType.MOVIE) 7.1f else 9.2f, rottenTomatoesScore = 80)) } }
        settle(); model.loadMore(); settle()
        assertEquals(16, model.state.value.items.size)
        assertTrue(ratingBatches.all { it.size <= 4 })
        assertEquals(16, ratingBatches.sumOf { it.size })
        assertTrue(model.state.value.items.all { it.ratings?.imdbScore == if (it.type == MediaType.MOVIE) 7.1f else 9.2f })
        val count = ratingBatches.size
        model.selectProvider(8); settle(); model.selectProvider(null); settle()
        assertEquals(count + 2, ratingBatches.size, "The new provider loads eight items; returning to All reuses its ratings")
    }

    @Test fun lateRatingsCannotOverwriteNewProviderSelection() = runTest(dispatcher) {
        val ratingsGate = CompletableDeferred<Unit>()
        enrich = { items ->
            if (model.state.value.selectedProviderId == 8) { withContext(NonCancellable) { ratingsGate.await() }; rated(items, 1.2f) }
            else rated(items, 9.3f)
        }
        settle(); model.selectProvider(8); runCurrent()
        model.selectProvider(9); runCurrent()
        assertEquals(9, model.state.value.selectedProviderId)
        ratingsGate.complete(Unit); settle()
        assertTrue(model.state.value.items.all { it.ratings?.imdbScore == 9.3f })
    }

    @Test fun lateRatingsCannotRestoreItemsAfterProfileChange() = runTest(dispatcher) {
        val ratingsGate = CompletableDeferred<Unit>()
        enrich = { withContext(NonCancellable) { ratingsGate.await() }; rated(it) }
        runCurrent()
        owner = "second"; configuration = configuration.copy(owner = owner, services = emptySet())
        changes.emit(Unit); runCurrent()
        ratingsGate.complete(Unit); settle()
        assertTrue(model.state.value.items.isEmpty()); assertTrue(model.state.value.serviceIds.isEmpty())
    }

    @Test fun unavailableOrSlowRatingsKeepPostersWithoutAvailabilityError() = runTest(dispatcher) {
        hydrate = { rated(it).map { item -> item.copy(ratings = item.ratings?.copy(rottenTomatoesScore = null)) } }
        enrich = { error("Ratings unavailable") }
        settle()
        assertNull(model.state.value.error); assertFalse(model.state.value.loading)
        assertTrue(model.state.value.items.all { it.ratings?.imdbScore == 8.4f })
        enrich = { delay(10_000); rated(it, 1f) }
        model.refresh(); settle()
        assertNull(model.state.value.error); assertFalse(model.state.value.loading)
        assertTrue(model.state.value.items.all { it.ratings?.imdbScore == 8.4f })
    }

    @Test fun largeRetainedPagesDoNotEnrichHundredsOfPostersAtOnce() = runTest(dispatcher) {
        itemsPerPage = 100
        enrich = { rated(it) }
        settle()
        assertEquals(200, model.state.value.items.size)
        assertEquals(40, ratingBatches.sumOf { it.size })
        assertTrue(ratingBatches.all { it.size <= 4 })
    }
}
