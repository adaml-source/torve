package com.torve.data.addon

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.torve.data.acceleration.AccelerationApi
import com.torve.data.auth.AuthClient
import com.torve.data.auth.DeviceRegistrationDto
import com.torve.data.debrid.DebridClient
import com.torve.db.TorveDatabase
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.InstalledAddon
import com.torve.domain.model.AddonManifest
import com.torve.domain.model.MediaType
import com.torve.domain.model.PremiumFeature
import com.torve.domain.model.RegexPattern
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.model.StreamGroup
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.Subscription
import com.torve.domain.model.SubscriptionTier
import com.torve.domain.repository.BackendPremiumResult
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.repository.SubscriptionRepository
import com.torve.domain.security.SecureStorage
import com.torve.domain.streams.StreamFilterPreferenceKeys
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamRepositoryImplRuntimeFilterTest {

    private val json = Json { encodeDefaults = true }

    @Test fun savedIndexersParticipateInAppleAndOtherAppLookupsWithoutInstalledExtensions() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine(io.ktor.client.engine.mock.MockEngineConfig().apply {
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            addHandler { request ->
            requests += request.url.host
            when (request.url.host) {
                "indexer.example" -> respond(if (request.url.parameters["t"] == "get") "<nzb><file/></nzb>" else
                    """<rss><channel><item><title>Movie.1080p</title><enclosure url="https://indexer.example/api?t=get&amp;id=1"/></item></channel></rss>""")
                "api.torbox.app" -> respond(when {
                    request.url.encodedPath.endsWith("createusenetdownload") -> """{"data":{"id":1}}"""
                    request.url.encodedPath.endsWith("mylist") -> """{"data":[{"download_state":"completed","files":[{"id":2,"name":"Movie.mkv","size":1000}]}]}"""
                    else -> """{"data":"https://cdn.example/movie.mkv"}"""
                })
                else -> error("Unexpected network request")
            }
        } }))
        val configured = com.torve.data.usenet.ConfiguredUsenetSources(
            com.torve.data.usenet.NewznabClient(http), com.torve.data.usenet.TorBoxUsenetClient(http),
            { com.torve.data.usenet.UsenetSourceConfiguration("owner", true,
                listOf(com.torve.data.panda.NzbIndexerRow("custom", "https://indexer.example", "indexer-key")),
                "torbox", "torbox-key") }, { "owner" })
        try {
            for (applePolicy in listOf(false, true)) {
                ExtensionRuntimePolicy.userManagedOnly = applePolicy
                val repo = repository(InMemoryPreferencesRepository(), false, httpClient = http,
                    configuredUsenet = configured) { emptyList() }
                assertTrue(repo.hasConfiguredUsenetSources())
                val result = repo.fetchTestStreamsWithFeedback()
                assertEquals(1, result.streams.size, "Source errors: ${result.sourceErrors}")
                assertEquals("https://cdn.example/movie.mkv", repo.resolveStreamWithFallback(result.streams.single(), emptyMap()).url)
                val before = requests.count { it == "indexer.example" }
                repo.fetchTestStreamsWithFeedback()
                assertEquals(before + 1, requests.count { it == "indexer.example" }, "An extension cache must not suppress saved indexers")
            }
        } finally { http.close(); ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test fun manualProviderCacheCheckDoesNotWaitForAnAccelerationReport() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val requests = mutableListOf<String>()
        val hash = "e".repeat(40)
        val http = HttpClient(MockEngine { request ->
            requests += request.url.host
            respond(if (request.url.host == "api.torbox.app")
                """{"success":true,"data":{"$hash":{"hash":"$hash"}}}""" else "{}",
                headers = io.ktor.http.headersOf("Content-Type", "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        try {
            val api = AccelerationApi(http, testAuthClient(http), json, { "https://api.torve.app" })
            assertEquals(mapOf(hash to true), DebridClient(http, json, api).checkCache(DebridServiceType.TORBOX, "fixture", listOf(hash)))
            assertEquals(listOf("api.torbox.app"), requests)
        } finally { ExtensionRuntimePolicy.userManagedOnly = previous; http.close() }
    }

    @Test fun manualDirectPlaybackNeverDependsOnAnAccelerationOutcomeRequest() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.host
            respond("{}", headers = io.ktor.http.headersOf("Content-Type", "application/json"))
        }) { install(ContentNegotiation) { json(json) } }
        try {
            val source = ParsedStream("Configured provider", "1080p", "Film",
                directUrl = "https://cdn.example/film.mkv", addonBaseUrl = "https://provider.example")
            val repo = repository(InMemoryPreferencesRepository(), true, httpClient = http) { listOf(source) }
            repo.fetchTestStreamsWithFeedback()
            assertEquals(source.directUrl, repo.resolveStream(source, DebridServiceType.TORBOX, "fixture").url)
            assertTrue(requests.isEmpty(), "A ready extension link must not wait for the backend")
        } finally { ExtensionRuntimePolicy.userManagedOnly = previous; http.close() }
    }

    @Test fun seasonPackPreferenceAlsoFiltersStreamsFromAlternateAggregationPaths() = runTest {
        val pack = stream("Show.S01.Complete.1080p")
        val episode = stream("Show.S01E02.1080p")
        val repo = repository(InMemoryPreferencesRepository(), false) { listOf(pack, episode) }
        assertEquals(listOf(episode), repo.fetchTestStreamsWithFeedback(
            streamPreferences = StreamPreferences(excludeSeasonPacks = true)).streams)
        assertEquals(2, repo.fetchTestStreamsWithFeedback().streams.size)
    }

    @Test fun gProfileApprovesTheHlsVariantActuallySelectedByApple() = runTest {
        var profile = "kids"
        var switchDuringResolve = false
        val controls = com.torve.data.profile.FamilyControls(MapDeviceSettings(mutableMapOf()), MapSecureStorage(mutableMapOf()),
            null, com.torve.presentation.settings.SettingsRefreshNotifier(),
            lookup = { listOf(com.torve.domain.model.AgeCertification("US", "G")) }, scope = backgroundScope,
            accountId = { "account" }, profileId = { profile }, profileRatingLimit = { com.torve.domain.model.ContentRating.G })
        controls.initialize()
        val source = ParsedStream(addonName="Approved", title="Toy Story 2", quality="1080p",
            directUrl="https://hoster.example/video", addonBaseUrl="https://sources.example")
        val client = HttpClient(MockEngine { request ->
            if (switchDuringResolve) { profile = "other"; controls.updateSession() }
            val body = when (request.url.encodedPath) {
                "/rest/1.0/unrestrict/link" -> """{"id":"ready","filename":"video.mp4","mimeType":"video/mp4","download":"https://cdn.example/original.mp4","streamable":1}"""
                "/rest/1.0/streaming/transcode/ready" -> """{"apple":{"full":"https://cdn.example/play.m3u8"},"liveMP4":{"full":"https://cdn.example/play.mp4"},"h264WebM":{"full":"https://cdn.example/play.webm"}}"""
                else -> error("Unexpected provider path")
            }
            respond(body, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"))
        })
        val repo=repository(InMemoryPreferencesRepository(),false,family=controls,httpClient=client) { listOf(source) }
        val addon=InstalledAddon("https://sources.example/manifest.json",AddonManifest("approved","Approved","1"))
        val selected=repo.fetchStreamsWithFeedback(MediaType.MOVIE,"tt0120363","863","Toy Story 2",null,null,
            listOf(addon),emptyMap(),StreamPreferences(),StreamFetchPolicy.FULL).streams.single()
        val resolved=repo.resolveStream(selected,DebridServiceType.REAL_DEBRID,"test-key")
        assertEquals("https://cdn.example/play.m3u8",resolved.transcodeUrls?.hls)
        assertTrue(controls.playbackAllowed(resolved.url))
        for (url in listOfNotNull(resolved.transcodeUrls?.hls,resolved.transcodeUrls?.mp4,resolved.transcodeUrls?.webm)) {
            assertTrue(controls.playbackAllowed(url),"Provider variant lost its title/source approval")
        }
        assertTrue(!controls.playbackAllowed("https://cdn.example/unapproved.mp4"))
        switchDuringResolve = true
        assertFailsWith<IllegalStateException> { repo.resolveStream(selected,DebridServiceType.REAL_DEBRID,"test-key") }
        assertTrue(!controls.playbackAllowed(resolved.url))
        assertTrue(!controls.playbackAllowed(resolved.transcodeUrls!!.hls!!))
    }


    @Test fun preparedRecoveryKeepsSourceApprovalAndRevokesItOnPolicyOrProfileChanges() = runTest {
        var profile="kids"
        var rating=com.torve.domain.model.ContentRating.G
        val controls=com.torve.data.profile.FamilyControls(MapDeviceSettings(mutableMapOf()),MapSecureStorage(mutableMapOf()),
            null,com.torve.presentation.settings.SettingsRefreshNotifier(),
            lookup={ listOf(com.torve.domain.model.AgeCertification("US","G")) },scope=backgroundScope,
            accountId={ "account" },profileId={ profile },profileRatingLimit={ rating })
        controls.initialize()
        val source=ParsedStream(addonName="Approved",title="Toy Story 2",quality="1080p",
            directUrl="https://sources.example/play/selected",addonBaseUrl="https://sources.example")
        val repo=repository(InMemoryPreferencesRepository(),false,family=controls) { listOf(source) }
        val prepared=com.torve.presentation.detail.DetailPlaybackStartupOrchestrator.preparedTorBoxStream(source,
            "https://nexus-123.ceur.tb-cdn.st/video.mp4","Toy Story 2.mp4",1000)!!
        assertFailsWith<IllegalStateException> { repo.authorizePreparedStream(source,prepared) }
        val addon=InstalledAddon("https://sources.example/manifest.json",AddonManifest("approved","Approved","1"))
        val selected=repo.fetchStreamsWithFeedback(MediaType.MOVIE,"tt0120363","863","Toy Story 2",null,null,
            listOf(addon),emptyMap(),StreamPreferences(),StreamFetchPolicy.FULL).streams.single()
        assertTrue(!controls.playbackAllowed(prepared.url))
        assertEquals(prepared,repo.authorizePreparedStream(selected,prepared))
        assertTrue(controls.playbackAllowed(prepared.url))
        controls.inheritPlayback(prepared.url,"https://cdn.example/redirect.mp4")
        assertTrue(controls.playbackAllowed("https://cdn.example/redirect.mp4"))
        assertFailsWith<IllegalStateException> { repo.authorizePreparedStream(source.copy(directUrl="https://sources.example/other"),prepared) }
        rating=com.torve.domain.model.ContentRating.PG;controls.updateSession()
        assertTrue(!controls.playbackAllowed(prepared.url))
        assertFailsWith<IllegalStateException> { repo.authorizePreparedStream(selected,prepared) }
        profile="other";controls.updateSession()
        assertTrue(!controls.playbackAllowed("https://cdn.example/redirect.mp4"))
        assertFailsWith<IllegalStateException> { repo.authorizePreparedStream(selected,prepared) }
    }

    @Test fun gRatingWithoutOptionalFamilyControlsCanPlayVerifiedToyStoryAndRedirects() = runTest {
        var profile = "kids"
        val controls = com.torve.data.profile.FamilyControls(MapDeviceSettings(mutableMapOf()), MapSecureStorage(mutableMapOf()),
            null, com.torve.presentation.settings.SettingsRefreshNotifier(),
            lookup = { listOf(com.torve.domain.model.AgeCertification("US", "G")) },
            scope = backgroundScope, accountId = { "account" }, profileId = { profile },
            profileRatingLimit = { com.torve.domain.model.ContentRating.G })
        controls.initialize()
        assertTrue(!controls.state.value.configuration.enabled)
        assertTrue(controls.state.value.isKids)
        val source = ParsedStream(addonName="Approved extension", title="Toy Story 2", quality="1080p",
            directUrl="https://sources.example/toy-story-2.mp4", addonBaseUrl="https://sources.example")
        val repo = repository(InMemoryPreferencesRepository(), false, family=controls) { listOf(source) }
        val addon = InstalledAddon("https://sources.example/manifest.json", AddonManifest("approved", "Approved", "1"))
        val streams = repo.fetchStreamsWithFeedback(MediaType.MOVIE,"tt0120363","863","Toy Story 2",null,null,
            listOf(addon),emptyMap(),StreamPreferences(),StreamFetchPolicy.FULL).streams
        assertEquals(1,streams.size)
        assertTrue(!controls.playbackAllowed(source.directUrl!!))
        val resolved = repo.resolveStream(streams.single(),null,"")
        assertTrue(controls.playbackAllowed(resolved.url))
        controls.inheritPlayback(resolved.url,"https://cdn.example/final.mp4")
        assertTrue(controls.playbackAllowed("https://cdn.example/final.mp4"))
        assertTrue(!controls.playbackAllowed("https://cdn.example/unapproved.mp4"))
        profile="other"; controls.updateSession()
        assertTrue(!controls.playbackAllowed(resolved.url))
        assertFailsWith<IllegalStateException> { repo.resolveStream(streams.single(),null,"") }
    }

    @Test fun gRatingCannotResolveAnUnfetchedSourceOrFetchAnAdultTitle() = runTest {
        var rating="G"
        val controls = com.torve.data.profile.FamilyControls(MapDeviceSettings(mutableMapOf()), MapSecureStorage(mutableMapOf()),
            null, com.torve.presentation.settings.SettingsRefreshNotifier(),
            lookup = { listOf(com.torve.domain.model.AgeCertification("US", rating)) },
            scope = backgroundScope, accountId = { "account" }, profileId = { "kids" },
            profileRatingLimit = { com.torve.domain.model.ContentRating.G })
        controls.initialize()
        val source=ParsedStream(addonName="Approved",title="Title",quality="1080p",directUrl="https://media.example/movie.mp4",addonBaseUrl="https://sources.example")
        val repo=repository(InMemoryPreferencesRepository(),false,family=controls) { listOf(source) }
        assertFailsWith<IllegalStateException> { repo.resolveStream(source,null,"") }
        rating="R"
        val addon=InstalledAddon("https://sources.example/manifest.json",AddonManifest("approved","Approved","1"))
        assertFailsWith<IllegalStateException> {
            repo.fetchStreamsWithFeedback(MediaType.MOVIE,"tt9999","9999","Adult",null,null,listOf(addon),emptyMap(),StreamPreferences(),StreamFetchPolicy.FULL)
        }
        assertTrue(!controls.playbackAllowed(source.directUrl!!))
    }

    @Test fun familySourceRestrictionsStillExcludeUnapprovedExtensions() = runTest {
        var profile="default"
        val controls = com.torve.data.profile.FamilyControls(MapDeviceSettings(mutableMapOf()), MapSecureStorage(mutableMapOf()),
            null, com.torve.presentation.settings.SettingsRefreshNotifier(),
            lookup = { listOf(com.torve.domain.model.AgeCertification("US", "G")) },
            scope = backgroundScope, accountId = { "account" }, profileId = { profile })
        controls.initialize(); controls.enable("1234","1234")
        controls.saveProfile("kids",true,com.torve.domain.model.KidsProfileRules(maximumAge=0,allowedSources=setOf("catalogue","extension:approved")))
        controls.authorizeProfileSwitch("kids"); profile="kids"; controls.updateSession()
        val approved=ParsedStream(addonName="Approved",title="Toy Story 2",quality="1080p",directUrl="https://sources.example/allowed.mp4",addonBaseUrl="https://sources.example")
        val denied=approved.copy(directUrl="https://media.example/blocked.mp4",addonBaseUrl="https://blocked.example")
        val repo=repository(InMemoryPreferencesRepository(),false,family=controls) { listOf(approved,denied) }
        val addons=listOf(InstalledAddon("https://sources.example/manifest.json",AddonManifest("approved","Approved","1")),
            InstalledAddon("https://blocked.example/manifest.json",AddonManifest("blocked","Blocked","1")))
        val streams=repo.fetchStreamsWithFeedback(MediaType.MOVIE,"tt0120363","863","Toy Story 2",null,null,addons,emptyMap(),StreamPreferences(),StreamFetchPolicy.FULL).streams
        assertEquals(1,streams.size)
        val resolved=repo.resolveStream(streams.single(),null,"")
        assertTrue(controls.playbackAllowed(resolved.url))
        assertFailsWith<IllegalStateException> { repo.resolveStream(denied,null,"") }
    }


    @Test
    fun userConfiguredExtensionResolvesReadyVideoWithoutPandaOrLocalServiceAccounts() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        try {
            val repo = repository(InMemoryPreferencesRepository(), false) { emptyList() }
            val url = "https://media.example/original-video.mp4?token=provider-supplied"
            val resolved = repo.resolveStream(
                ParsedStream(addonName = "Independent playback", quality = "1080p", title = "Original video",
                    directUrl = url, addonBaseUrl = "https://independent.example/u/config"),
                provider = null, apiKey = "",
            )
            assertEquals(url, resolved.url)
            assertEquals(null, resolved.service)
        } finally { ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test
    fun rawNzbRequiresProviderPreparationInsteadOfSuggestingPanda() = runTest {
        val previous = ExtensionRuntimePolicy.userManagedOnly
        ExtensionRuntimePolicy.userManagedOnly = true
        try {
            val repo = repository(InMemoryPreferencesRepository(), false) { emptyList() }
            for (url in listOf("https://indexer.example/file.nzb?apikey=example", "https://independent.example/file.NZB?key=example")) {
                val failure = assertFailsWith<Exception> {
                    repo.resolveStream(
                        ParsedStream(addonName = "Independent indexer", quality = "1080p", title = "Original video",
                            directUrl = url, addonBaseUrl = "https://independent.example"),
                        provider = null, apiKey = "",
                    )
                }
                assertTrue(failure.message.orEmpty().contains("playable video link"))
                assertTrue(!failure.message.orEmpty().contains("Panda"))
            }
        } finally { ExtensionRuntimePolicy.userManagedOnly = previous }
    }

    @Test
    fun changingEnabledAddonsOrPreferencesInvalidatesTheSourceCache() = runTest {
        var calls = 0
        val repo = repository(InMemoryPreferencesRepository(), false) {
            calls++
            listOf(stream("The.Office.US.S01E05.1080p"))
        }
        val addon = InstalledAddon("https://panda.example/manifest.json", AddonManifest("panda", "Panda", "1"))
        repo.fetchTestStreamsWithFeedback(addons = listOf(addon))
        repo.fetchTestStreamsWithFeedback(addons = listOf(addon))
        assertEquals(1, calls)
        repo.fetchTestStreamsWithFeedback(addons = listOf(addon.copy(isEnabled = false)))
        assertEquals(2, calls)
        repo.fetchTestStreamsWithFeedback(addons = listOf(addon), streamPreferences = StreamPreferences(cachedOnly = false))
        assertEquals(3, calls)
    }

    @Test
    fun fetchStreamsAppliesPersistedPremiumRegexAndStreamGroups() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(
                listOf(
                    StreamGroup(name = "4K DV", matchPattern = "(?i)2160p", priority = 0),
                    StreamGroup(name = "1080p", matchPattern = "(?i)1080p", priority = 1),
                ),
            ),
        )

        val repository = repository(
            streams = testStreams(),
            preferences = preferences,
            premium = true,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(
            listOf(
                "Movie.2026.2160p.WEB-DL.DV.Atmos",
                "Movie.2026.1080p.WEB-DL",
            ),
            result.streams.map { it.title },
        )
        assertEquals(1, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsAppliesUserRulesWithoutPremium() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(listOf(StreamGroup(name = "4K DV", matchPattern = "(?i)2160p", priority = 0))),
        )
        val streams = testStreams()
        val repository = repository(
            streams = streams,
            preferences = preferences,
            premium = false,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(
            listOf(
                "Movie.2026.2160p.WEB-DL.DV.Atmos",
                "Movie.2026.1080p.WEB-DL",
            ),
            result.streams.map { it.title },
        )
        assertEquals(1, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsIgnoresInvalidPersistedRegexWithoutCrashing() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "Broken", pattern = "["))),
        )
        preferences.setString(
            StreamFilterPreferenceKeys.STREAM_GROUPS,
            json.encodeToString(listOf(StreamGroup(name = "Broken group", matchPattern = "[", priority = 0))),
        )
        val streams = testStreams()
        val repository = repository(
            streams = streams,
            preferences = preferences,
            premium = true,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(streams.map { it.title }, result.streams.map { it.title })
        assertEquals(0, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsReportsWhenAllStreamsAreHiddenByFilters() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "Hide movie", pattern = "(?i)Movie\\.2026"))),
        )
        val streams = testStreams()
        val repository = repository(
            streams = streams,
            preferences = preferences,
            premium = true,
        )

        val result = repository.fetchTestStreamsWithFeedback()

        assertEquals(emptyList(), result.streams)
        assertEquals(streams.size, result.filterFeedback.hiddenCount)
    }

    @Test
    fun fetchStreamsWithFeedbackScopesHiddenCountToTheSameFetchResult() = runTest {
        val preferences = InMemoryPreferencesRepository()
        preferences.setString(
            StreamFilterPreferenceKeys.REGEX_PATTERNS,
            json.encodeToString(listOf(RegexPattern(label = "No cams", pattern = "(?i)HDCAM"))),
        )
        val cleanStream = stream("Other.2026.1080p.WEB-DL")
        val repository = repository(
            preferences = preferences,
            premium = true,
            streamsForImdb = { imdbId ->
                if (imdbId == "tt-hidden") {
                    listOf(stream("Movie.2026.HDCAM.x264"), stream("Movie.2026.1080p.WEB-DL"))
                } else {
                    listOf(cleanStream)
                }
            },
        )

        val hiddenResult = repository.fetchTestStreamsWithFeedback(imdbId = "tt-hidden")
        val cleanResult = repository.fetchTestStreamsWithFeedback(imdbId = "tt-clean")

        assertEquals(1, hiddenResult.filterFeedback.hiddenCount)
        assertEquals(0, cleanResult.filterFeedback.hiddenCount)
        assertEquals(listOf(cleanStream.title), cleanResult.streams.map { it.title })
    }

    private fun repository(
        streams: List<ParsedStream>,
        preferences: PreferencesRepository,
        premium: Boolean,
    ): StreamRepositoryImpl = repository(
        preferences = preferences,
        premium = premium,
        streamsForImdb = { streams },
    )

    private fun repository(
        preferences: PreferencesRepository,
        premium: Boolean,
        family: com.torve.data.profile.FamilyControls? = null,
        httpClient: HttpClient = HttpClient(MockEngine { error("Unexpected network request") }),
        configuredUsenet: com.torve.data.usenet.ConfiguredUsenetSources? = null,
        streamsForImdb: (String) -> List<ParsedStream>,
    ): StreamRepositoryImpl {
        val authClient = testAuthClient(httpClient)
        val accelerationApi = AccelerationApi(
            httpClient = httpClient,
            authClient = authClient,
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            baseUrlProvider = { "https://api.torve.app" },
        )
        return StreamRepositoryImpl(
            debridClient = DebridClient(httpClient, json, accelerationApi),
            streamAggregationSource = StreamAggregationSource { _: List<InstalledAddon>,
                _: MediaType,
                imdbId: String,
                _: Int?,
                _: Int?,
                _: Map<DebridServiceType, String>,
                _: StreamPreferences,
                _: StreamFetchPolicy ->
                streamsForImdb(imdbId)
            },
            database = freshDb(),
            accelerationApi = accelerationApi,
            httpClient = httpClient,
            telemetry = NoOpTelemetryEmitter(),
            preferencesRepository = preferences,
            subscriptionRepository = FakeSubscriptionRepository(premium),
            family = family,
            configuredUsenet = configuredUsenet,
        )
    }

    private suspend fun StreamRepositoryImpl.fetchTestStreamsWithFeedback(
        imdbId: String = "tt1234567",
        addons: List<InstalledAddon> = emptyList(),
        streamPreferences: StreamPreferences = StreamPreferences(),
    ) = fetchStreamsWithFeedback(
        type = MediaType.MOVIE,
        imdbId = imdbId,
        contentId = "tmdb:movie:123",
        title = "Movie",
        season = null,
        episode = null,
        addons = addons,
        debridAccounts = emptyMap(),
        preferences = streamPreferences,
        fetchPolicy = StreamFetchPolicy.FULL,
    )

    private fun freshDb(): TorveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TorveDatabase.Schema.create(driver)
        return TorveDatabase(driver)
    }

    private fun testStreams(): List<ParsedStream> = listOf(
        stream("Movie.2026.1080p.WEB-DL"),
        stream("Movie.2026.HDCAM.x264"),
        stream("Movie.2026.2160p.WEB-DL.DV.Atmos", quality = "2160p"),
    )

    private fun stream(
        title: String,
        quality: String = "1080p",
    ): ParsedStream = ParsedStream(
        addonName = "Panda",
        quality = quality,
        title = title,
        source = "Real-Debrid",
        codec = "HEVC",
        hdr = "DV",
        audioCodec = "Atmos",
        isCached = true,
        score = 80,
    )

    private fun testAuthClient(httpClient: HttpClient): AuthClient {
        return AuthClient(
            localSettingsRepository = MapDeviceSettings(
                mutableMapOf(
                    AuthClient.KEY_AUTH_EMAIL to "user@torve.app",
                    AuthClient.KEY_AUTH_USER_ID to "user-1",
                    AuthClient.KEY_AUTH_IS_VERIFIED to "true",
                ),
            ),
            secureStorage = MapSecureStorage(
                mutableMapOf(
                    AuthClient.KEY_AUTH_ACCESS_TOKEN to "access-token",
                    AuthClient.KEY_AUTH_REFRESH_TOKEN to "refresh-token",
                    "auth_token_expires_at" to "4102444800000",
                ),
            ),
            httpClient = httpClient,
            baseUrlProvider = { "https://api.torve.app" },
            deviceRegistrationProvider = {
                DeviceRegistrationDto(
                    installation_id = "install-stream-filter-test",
                    device_name = "Desktop Test",
                    device_type = "desktop",
                    platform = "desktop",
                )
            },
        )
    }

    private class InMemoryPreferencesRepository : PreferencesRepository {
        private val values = mutableMapOf<String, String>()
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FakeSubscriptionRepository(
        private val premium: Boolean,
    ) : SubscriptionRepository {
        override suspend fun getActiveSubscription(): Subscription? = if (premium) {
            Subscription(
                id = "sub-1",
                tier = SubscriptionTier.MONTHLY,
                purchaseToken = "backend_entitlement",
                expiresAt = null,
                isActive = true,
                platform = "backend",
                purchasedAt = 1L,
            )
        } else {
            null
        }

        override suspend fun isPro(): Boolean = premium
        override suspend fun hasAccess(feature: PremiumFeature): Boolean = premium
        override suspend fun hasLocallyVerifiedPremiumAccess(): Boolean = premium
        override suspend fun activateSubscription(tier: SubscriptionTier, purchaseToken: String) = Unit
        override suspend fun ensureFreeTier() = Unit
        override suspend fun restorePurchase(purchaseToken: String): Subscription? = getActiveSubscription()
        override suspend fun refreshFromBackend(): Boolean = premium
        override suspend fun refreshFromBackendDetailed(): BackendPremiumResult =
            if (premium) BackendPremiumResult.Active else BackendPremiumResult.NoEntitlement
        override suspend fun onBackendEntitlementGranted(isPremium: Boolean) = Unit
    }

    private class MapDeviceSettings(
        private val values: MutableMap<String, String>,
    ) : DeviceLocalSettingsRepository {
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun setString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private class MapSecureStorage(
        private val values: MutableMap<String, String>,
    ) : SecureStorage {
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun putString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}
