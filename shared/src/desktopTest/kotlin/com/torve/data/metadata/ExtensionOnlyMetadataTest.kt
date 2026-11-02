package com.torve.data.metadata

import com.torve.data.addon.*
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.apple.AppleCatalogueDetail
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlin.test.*

class ExtensionOnlyMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun addon(url: String = "https://configured-tmdb-addon.baby-beamup.club/german/manifest.json") = InstalledAddon(url,
        AddonManifest("user-meta", "User metadata", "1", resources = listOf("catalog", "meta"),
            types = listOf("movie", "series"), idPrefixes = listOf("tmdb:", "tt"),
            catalogs = listOf(AddonCatalog("movie", "tmdb.search", extra = listOf(AddonExtra("search"), AddonExtra("skip"))))))
    private fun seed(id: String = "tmdb:42") = MediaItem(id, tmdbId = 42, type = MediaType.SERIES,
        title = "Old English title", overview = "Old English plot", posterUrl = "https://old.invalid/poster")

    @Test fun legacyConsentAndAgeLookupCannotEnableAnyOperatorRequest() = runTest {
        val old = ExtensionRuntimePolicy.userMetadataOnly
        ExtensionRuntimePolicy.userMetadataOnly = true
        var requests = 0; var dns = 0
        val http = HttpClient(MockEngine { requests++; error("Operator request must never execute") })
        val prefs = object : PreferencesRepository {
            override suspend fun getString(key: String) = "true"
            override suspend fun setString(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        try {
            assertEquals(TmdbExtensionSelection(), prefs.tmdbExtensionSelection())
            val api = TmdbApiClient(http, { "legacy-key" }, { dns++; null }, requestAllowed = { true })
            assertFailsWith<TmdbExtensionDisabledException> { api.searchMulti("film") }
            assertFailsWith<TmdbExtensionDisabledException> { api.getMovieDetail(42) }
            assertFailsWith<TmdbExtensionDisabledException> { api.titleCompletions("film") }
            assertFailsWith<TmdbExtensionDisabledException> { api.getWatchProviderLogosFromBackend("https://api.torve.app", "movie", "US") }
            assertEquals(0, requests); assertEquals(0, dns)
        } finally { http.close(); ExtensionRuntimePolicy.userMetadataOnly = old }
    }

    @Test fun configuredCatalogueAndPaginationUseOnlyTheUsersExactUrl() = runTest {
        val old = ExtensionRuntimePolicy.userMetadataOnly
        ExtensionRuntimePolicy.userMetadataOnly = true
        val urls = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            val offset = if (request.url.encodedPath.contains("skip=100")) 100 else 0
            respond(json.encodeToString(StremioCatalogResponse.serializer(), StremioCatalogResponse((offset until offset+100).map {
                StremioMeta("tmdb:$it", "movie", "Title $it", poster = "https://image.tmdb.org/t/p/w342/$it.jpg", imdbRating = "8.2") })))
        })
        try {
            val client = StremioAddonClient(http, json, nativeCatalogueBaseUrl = "https://api.torve.app/apple-metadata/v1")
            val rows = listOf(0,100).flatMap { offset -> client.fetchCatalog("https://configured-tmdb-addon.baby-beamup.club/german", "movie", "tmdb.language", skip = offset).metas }
            assertEquals(200, rows.size); assertEquals(200, rows.map { it.id }.distinct().size)
            assertTrue(rows.all { it.toMediaItem().ratings?.imdbScore == 8.2f })
            assertTrue(urls.all { it.startsWith("https://configured-tmdb-addon.baby-beamup.club/german/catalog/") })
            assertTrue(urls.last().endsWith("skip=100.json"))
            assertNull(nativeCatalogueId("https://aiostreams.example/config", "78de3b0.tmdb.language"))
        } finally { http.close(); ExtensionRuntimePolicy.userMetadataOnly = old }
    }

    @Test fun metadataProviderOwnsLocalizedTextArtworkRatingsAndLongSeasons() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/german/meta/series/tmdb:42.json", request.url.encodedPath)
            val meta = StremioMeta("tmdb:42", "series", "Deutscher Titel", description = "Deutsche Handlung",
                poster = "https://image.tmdb.org/poster.jpg", logo = "https://image.tmdb.org/logo.png", imdbRating = "8.7",
                cast = listOf("Actor"), videos = (1..134).map { StremioVideo("tmdb:42:31:$it", "Episode $it", 31, it, overview = "Story $it") })
            respond(json.encodeToString(StremioMetaResponse.serializer(), StremioMetaResponse(meta)))
        })
        try {
            val repo = ExtensionMetadataRepository(StubAddonRepository(listOf(addon())), StremioAddonClient(http,json), { "profile-a" }, { "de" })
            val detail = repo.resolve(seed())
            assertEquals("Deutscher Titel", detail.title); assertEquals("Deutsche Handlung", detail.overview)
            assertEquals("https://image.tmdb.org/poster.jpg", detail.posterUrl)
            assertEquals("https://image.tmdb.org/logo.png", detail.logoUrl); assertEquals(8.7f, detail.ratings?.imdbScore)
            assertEquals("Actor",detail.cast.single().name); assertEquals(134, detail.seasons.single().episodes.size)
            assertEquals("Story 134", repo.getSeasonDetail(42,31).episodes.last().overview)
            assertEquals(1,http.engine.let { (it as MockEngine).requestHistory.size })
        } finally { http.close() }
    }

    @Test fun cachesAreScopedToAccountLanguageAndInstalledConfigurationAndDisableImmediately() = runTest {
        var owner = "one"; var locale = "en"; var requests = 0
        val installed = StubAddonRepository(listOf(addon()))
        val http = HttpClient(MockEngine { request -> requests++
            respond("""{"meta":{"id":"tmdb:42","type":"series","name":"Provider title","description":"Provider plot"}}""") })
        try {
            val repo = ExtensionMetadataRepository(installed,StremioAddonClient(http,json),{ owner },{ locale })
            repo.resolve(seed()); repo.resolve(seed()); assertEquals(1,requests)
            locale="de"; repo.resolve(seed()); assertEquals(2,requests)
            owner="two"; repo.resolve(seed()); assertEquals(3,requests)
            installed.enabled=listOf(addon("https://configured-tmdb-addon.baby-beamup.club/changed/manifest.json"))
            repo.resolve(seed()); assertEquals(4,requests)
            installed.enabled=emptyList(); assertEquals(seed(),repo.resolve(seed())); assertTrue(repo.cached(listOf("42"),"tv",locale).isEmpty())
            assertNull(repo.presentation("tt1234567","movie")); assertTrue(repo.getHomeShelves().isEmpty())
            assertTrue(repo.searchMulti("film").isEmpty()); assertEquals(4,requests)
        } finally { http.close() }
    }

    @Test fun knownImdbAliasesAreAcceptedButArbitraryRedirectsAreRejected() = runTest {
        val selected=seed().copy(imdbId="tt1234567")
        val provider=addon().copy(manifest=addon().manifest.copy(idPrefixes=listOf("tt")))
        val good=AppleCatalogueDetail.resolve(selected,listOf(provider), { _,_,id ->
            assertEquals("tt1234567",id); StremioMeta(id,"series","German") },{ error("No operator enrichment") },extensionAuthoritative=true)
        assertEquals(selected.id,good.id);assertEquals("German",good.title)
        val bad=AppleCatalogueDetail.resolve(selected,listOf(provider),{ _,_,_ -> StremioMeta("tt9999999","series","Wrong") },{ null },extensionAuthoritative=true)
        assertEquals(selected,bad)
    }

    @Test fun realTmdbExtensionResponseKeepsAllEpisodesAndAcceptsOnlyVerifiedImdbMapping() = runTest {
        // This is the public provider's actual response shape: IMDb request, TMDB result ID, videos named `name`.
        val payload = java.io.File("../build/apple-metadata-deploy/user-tmdb-daily-show.json").readText()
        assertTrue(payload.toByteArray().size > 2_000_000)
        val parsed = json.decodeFromString<StremioMetaResponse>(payload).meta!!
        assertEquals("tmdb:2224", parsed.id); assertEquals("tt0115147", parsed.imdbId)
        val http = HttpClient(MockEngine { request ->
            respond(if (request.url.encodedPath.contains("/catalog/")) "{\"metas\":[" + json.parseToJsonElement(payload).let { (it as kotlinx.serialization.json.JsonObject)["meta"].toString() } + "]}" else payload)
        })
        try {
            val installed = addon().copy(manifest=addon().manifest.copy(idPrefixes=listOf("tt")))
            val repo=ExtensionMetadataRepository(StubAddonRepository(listOf(installed)),StremioAddonClient(http,json),{"one"},{"en"})
            val item=repo.presentation("tt0115147","tv")!!
            assertEquals("tt0115147",item.id); assertEquals(2224,item.tmdbId)
            assertTrue(item.seasons.sumOf { it.episodes.size } > 4000)
            assertTrue(item.seasons.flatMap { it.episodes }.all { it.name.isNotBlank() })
            assertNotNull(item.logoUrl); assertNotNull(item.ratings?.imdbScore)
            val catalogue=StremioAddonClient(http,json).fetchCatalog("https://configured.example/en", "series", "tmdb.search", search="The Daily Show", failOnError=true)
            assertEquals(1,catalogue.metas.size)
            assertTrue(catalogue.metas.single().videos.size > 4000)
        } finally { http.close() }
    }

    @Test fun sparseSavedNumericTitleUsesTheExtensionsTmdbPrefix() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            respond("""{"meta":{"id":"tmdb:334541","type":"movie","name":"Manchester by the Sea","description":"Provider synopsis","background":"https://images.example/backdrop.jpg"}}""")
        })
        try {
            val repo = ExtensionMetadataRepository(StubAddonRepository(listOf(addon())), StremioAddonClient(http,json), { "one" }, { "en" })
            val sparse = MediaItem("334541", type = MediaType.MOVIE, title = "Manchester by the Sea")
            val detail = repo.resolve(sparse)
            assertEquals(sparse.id, detail.id)
            assertEquals("Provider synopsis", detail.overview)
            assertEquals("https://images.example/backdrop.jpg", detail.backdropUrl)
            assertEquals(listOf("/german/meta/movie/tmdb:334541.json"), requests)
        } finally { http.close() }
    }

    @Test fun slowTitleDoesNotBlockAnotherTitleOrCachedReadsAndDuplicateRequestsCoalesce() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = HttpClient(MockEngine { request ->
            val id = request.url.encodedPath.substringAfterLast('/').removeSuffix(".json")
            if (id == "tt1000001") { calls.incrementAndGet(); entered.complete(Unit); release.await() }
            respond("""{"meta":{"id":"$id","type":"movie","name":"Provider $id","description":"Plot $id"}}""")
        })
        try {
            val repo = ExtensionMetadataRepository(StubAddonRepository(listOf(addon())), StremioAddonClient(http,json), { "one" }, { "en" })
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val cached = repo.presentation("tt1000003", "movie")
                val slow = async { repo.presentation("tt1000001", "movie") }
                entered.await()
                val duplicate = async { repo.presentation("tt1000001", "movie") }
                try {
                    val fast = kotlinx.coroutines.withTimeoutOrNull(1000) { repo.presentation("tt1000002", "movie") }
                    assertNotNull(fast, "An unrelated extension request must not queue behind the slow title")
                    assertEquals(cached, kotlinx.coroutines.withTimeoutOrNull(1000) { repo.cached(listOf("tt1000003"), "movie", "en").single() })
                } finally { release.complete(Unit) }
                assertEquals(slow.await(), duplicate.await())
                assertEquals(1, calls.get(), "Concurrent consumers of one title share its request")
            }
        } finally { release.complete(Unit); http.close() }
    }

    @Test fun pendingResponseCannotSurviveAnUninstallAndCancelledConsumerDoesNotCancelAnother() = runTest {
        val installed = StubAddonRepository(listOf(addon()))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            entered.complete(Unit); release.await()
            respond("""{"meta":{"id":"tt1000001","type":"movie","name":"Provider title","description":"Provider synopsis"}}""")
        })
        try {
            val repo = ExtensionMetadataRepository(installed, StremioAddonClient(http,json), { "one" }, { "en" })
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val first = async { repo.presentation("tt1000001", "movie") }
                entered.await()
                val second = async { repo.resolve(MediaItem("tt1000001", type=MediaType.MOVIE, title="Saved title")) }
                first.cancel()
                // One caller leaving must not cancel the shared request. Uninstall still invalidates it.
                installed.enabled = emptyList()
                release.complete(Unit)
                assertEquals("Saved title", second.await().title)
                assertTrue(repo.cached(listOf("tt1000001"), "movie", "en").isEmpty())
            }
        } finally { release.complete(Unit); http.close() }
    }

    @Test fun selectedCatalogueSourceBypassesASlowProxyAndLanguageInstancesNeverShareDetails() = runTest {
        val english = addon("https://configured.example/en/manifest.json").copy(priority=0)
        val german = addon("https://configured.example/de/manifest.json").copy(priority=2)
        val proxy = addon("https://slow-proxy.example/manifest.json").copy(priority=1)
        val urls = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            assertNotEquals("slow-proxy.example",request.url.host)
            val locale = request.url.encodedPath.substringBefore("/meta").removePrefix("/")
            respond("""{"meta":{"id":"tmdb:42","type":"series","name":"Title $locale","description":"Plot $locale","videos":[{"id":"tmdb:42:1:1","season":1,"episode":1,"name":"Episode $locale"}]}}""")
        })
        try {
            val repo = ExtensionMetadataRepository(StubAddonRepository(listOf(english,proxy,german)),StremioAddonClient(http,json),{"one"},{"de"})
            fun selected(addon:InstalledAddon) = seed().copy(metadataSourceKey=com.torve.domain.player.MediaIdentityFactory.stableHash(addon.manifestUrl))
            val de=repo.resolve(selected(german));val en=repo.resolve(selected(english));val again=repo.resolve(selected(german))
            assertEquals("Title de",de.title);assertEquals("Title en",en.title);assertEquals(de,again)
            assertEquals("Episode de",de.seasons.single().episodes.single().name)
            assertEquals(2,urls.size)
            assertTrue(urls[0].contains("/de/meta/"));assertTrue(urls[1].contains("/en/meta/"))
        } finally { http.close() }
    }

    @Test fun configuredReturnImdbIdNormalizesATmdbRequestWithoutLosingItsPlaybackIdentity() = runTest {
        val payload = checkNotNull(javaClass.getResourceAsStream("/metadata/manchester-return-imdb.json")).bufferedReader().use { it.readText() }
        val meta=json.decodeFromString<StremioMetaResponse>(payload).meta!!
        assertEquals("tt4034228",meta.id);assertEquals(meta.id,meta.imdbId)
        val urls=mutableListOf<String>()
        val http=HttpClient(MockEngine { request -> urls+=request.url.encodedPath;respond(payload) })
        try {
            val repo=ExtensionMetadataRepository(StubAddonRepository(listOf(addon())),StremioAddonClient(http,json),{"one"},{"en"})
            val sparse=MediaItem("334541",type=MediaType.MOVIE,title="Manchester by the Sea")
            val detail=repo.resolve(sparse)
            assertEquals("334541",detail.id);assertEquals(334541,detail.tmdbId);assertEquals("tt4034228",detail.imdbId)
            assertEquals(meta.description,detail.overview);assertEquals(meta.background,detail.backdropUrl)
            assertEquals("Casey Affleck",detail.cast.first().name)
            assertEquals(listOf("/german/meta/movie/tmdb:334541.json"),urls)
            assertEquals(meta.description,repo.presentation("tt4034228","movie")?.overview)
            assertEquals(1,urls.size)
        } finally { http.close() }
    }

    @Test fun ratingOnlyExtensionOverridesMetadataUsingOnlyItsInstalledConfiguredUrl() = runTest {
        val calls = mutableListOf<String>()
        val rating = InstalledAddon("https://ratings-user.example/config/manifest.json", AddonManifest("imdb.ratings.local", "IMDb Ratings", "1",
            resources=listOf("stream"), types=listOf("movie","series")))
        val installed = StubAddonRepository(listOf(addon(), rating))
        val http = HttpClient(MockEngine { req ->
            calls += req.url.toString()
            if (req.url.host == "ratings-user.example") respond("""{"streams":[{"name":"IMDb Rating","description":"IMDb: 9.2/10 (21,386 votes)","externalUrl":"https://www.imdb.com/title/tt45368240/"}]}""")
            else respond("""{"meta":{"id":"tmdb:42","imdb_id":"tt45368240","type":"movie","name":"Deutscher Titel","imdbRating":"7.9"}}""")
        })
        try {
            val repo = ExtensionMetadataRepository(installed, StremioAddonClient(http,json), { "one" }, { "de" })
            val selected = seed().copy(type=MediaType.MOVIE)
            val detail = repo.resolve(selected)
            assertEquals(9.2f,detail.ratings?.imdbScore); assertEquals(21386,detail.ratings?.imdbVotes)
            assertEquals("Deutscher Titel",detail.title); assertEquals(selected.id,detail.id)
            assertTrue(calls.contains("https://ratings-user.example/config/stream/movie/tt45368240.json"))
            assertEquals(2,calls.size)
            repo.resolve(selected); assertEquals(2,calls.size)
            installed.enabled=listOf(addon())
            assertEquals(7.9f,repo.resolve(selected).ratings?.imdbScore)
            assertEquals(3,calls.size)
        } finally { http.close() }
    }

    @Test fun knownImdbCardUsesOnlyRatingsFeedAndScopesItsCacheToProfileLanguageAndConfiguration() = runTest {
        var owner="one";var locale="en";var count=0
        val rating = InstalledAddon("https://ratings-user.example/config/manifest.json", AddonManifest("imdb.ratings.local", "IMDb Ratings", "1",resources=listOf("stream")))
        val installed=StubAddonRepository(listOf(rating))
        val http=HttpClient(MockEngine { req ->
            assertTrue(req.url.encodedPath.startsWith("/config/stream/"));count++
            respond("""{"streams":[{"description":"IMDb: 9.2/10","externalUrl":"https://www.imdb.com/title/tt45368240/"}]}""")
        })
        try {
            val repo=ExtensionMetadataRepository(installed,StremioAddonClient(http,json),{owner},{locale})
            val item=MediaItem("tt45368240",type=MediaType.MOVIE,title="Seed",ratings=MediaRatings(imdbScore=7.9f))
            assertEquals(9.2f,repo.ratings(item).ratings?.imdbScore);repo.ratings(item);assertEquals(1,count)
            owner="two";repo.ratings(item);assertEquals(2,count)
            locale="de";repo.ratings(item);assertEquals(3,count)
            installed.enabled=listOf(rating.copy(manifest=rating.manifest.copy(version="2")));repo.ratings(item);assertEquals(4,count)
            installed.enabled=emptyList();assertEquals(item,repo.ratings(item));assertEquals(4,count)
        } finally { http.close() }
    }

    private class StubAddonRepository(var enabled: List<InstalledAddon>) : AddonRepository {
        override suspend fun installAddon(
            url: String, enabled: Boolean, priority: Int?, serverId: String?,
            syncedAt: Long?, installedFrom: String,
        ): InstalledAddon = error("not used")
        override suspend fun removeAddon(manifestUrl: String) {}
        override suspend fun getInstalledAddons(): List<InstalledAddon> = enabled
        override suspend fun getEnabledAddons(): List<InstalledAddon> = enabled
        override suspend fun toggleAddon(manifestUrl: String, enabled: Boolean) {}
        override suspend fun reorderAddons(orderedUrls: List<String>) {}
        override suspend fun getManifest(url: String) = error("not used")
        override suspend fun getAddon(manifestUrl: String): InstalledAddon? = null
        override suspend fun markAddonSynced(
            manifestUrl: String, serverId: String, syncedAt: Long?, installedFrom: String,
        ) {}
        override suspend fun syncRemoteState(
            manifestUrl: String, serverId: String, enabled: Boolean,
            priority: Int, syncedAt: Long, installedFrom: String,
        ) {}
        override suspend fun clearSyncMetadata() {}
        override suspend fun setAddonConfigId(manifestUrl: String, configId: String?) {}
    }

}
