package com.torve.android.ui.community

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.detail.PersonScreen
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.mdblist.*
import com.torve.data.metadata.*
import com.torve.data.ratings.OmdbClient
import com.torve.domain.integrations.*
import com.torve.domain.model.*
import com.torve.domain.repository.*
import com.torve.presentation.apple.ApplePlaybackSynopsis
import com.torve.presentation.detail.PersonViewModel
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.presentation.settings.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/** Production Compose screens and real repositories, isolated from household lists and keys. */
@OptIn(ExperimentalTestApi::class)
class CommunityParityDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private lateinit var originalPrefs: PreferencesRepository
    private lateinit var originalTmdb: TmdbApiClient
    private lateinit var originalMdb: MdbListApi
    private lateinit var originalLists: MdbListRepository
    private lateinit var originalSecrets: IntegrationSecretStore
    private lateinit var originalOmdb: OmdbClient
    private lateinit var originalEnricher: RatingsEnricher
    private lateinit var client: HttpClient
    private lateinit var secrets: IntegrationSecretStore
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val preferences = object : PreferencesRepository {
        override suspend fun getString(key: String) = values[key]
        override suspend fun setString(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private val longText = (1..12).joinToString("\n\n") { "Chapter $it. " + "A long, complete description of the story with readable paragraphs. ".repeat(7) } + "\n\nThe final paragraph ends here."
    private val tv get() = com.torve.android.device.DeviceFormFactor.isTv(rule.activity)

    @Before fun fixture() {
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val koin = getKoin()
        originalPrefs = koin.get(); originalTmdb = koin.get(); originalMdb = koin.get()
        originalLists = koin.get(); originalSecrets = koin.get(); originalOmdb = koin.get(); originalEnricher = koin.get()
        secrets = object : IntegrationSecretStore by originalSecrets {
            override suspend fun get(key: IntegrationSecretKey, subKey: String?) = if (key == IntegrationSecretKey.MDBLIST_API_KEY) "fixture-key" else null
            override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) { values["secret:$key"] = value }
            override suspend fun remove(key: IntegrationSecretKey, subKey: String?) { values.remove("secret:$key") }
        }
        client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/collection/99") -> """{"id":99,"name":"MCU franchise","parts":[${movies(1..24)}]}"""
                path.endsWith("/movie/42/lists") -> """{"page":1,"total_pages":1,"results":[{"id":501,"name":"All character movies","item_count":24},{"id":502,"name":"Whole universe","item_count":40}]}"""
                path.endsWith("/search/collection") -> """{"page":1,"results":[{"id":99,"name":"MCU franchise"}]}"""
                path.endsWith("/list/501") || path.endsWith("/list/502") -> {
                    val page = request.url.parameters["page"] ?: "1"
                    """{"id":"${path.substringAfterLast('/')}","name":"All character movies","item_count":24,"items":[${movies(if (page == "1") 1..20 else 21..24)}]}"""
                }
                path == "/lists/user" -> """[{"id":301,"name":"My personal list","items":24}]"""
                path == "/lists/top" -> """[{"id":302,"name":"Popular list","items":24}]"""
                path == "/lists/search" -> """[{"id":303,"name":"Search result list","items":24}]"""
                path.startsWith("/lists/") -> """{"id":${path.substringAfterLast('/').toIntOrNull() ?: 301},"name":"My personal list","items":24}"""
                else -> "{}"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        koin.declare<PreferencesRepository>(preferences, allowOverride = true)
        koin.declare<IntegrationSecretStore>(secrets, allowOverride = true)
        koin.declare(TmdbApiClient(client, apiKeyProvider = { "fixture" }, resolvedHostProvider = { null }, diagnostics = {}), allowOverride = true)
        koin.declare(MdbListApi(client), allowOverride = true)
        koin.declare(MdbListRepository(koin.get(), preferences), allowOverride = true)
        koin.declare(OmdbClient(client, preferences, secrets), allowOverride = true)
        koin.declare(RatingsEnricher(koin.get(), koin.get(), com.torve.data.trakt.TraktClient(client, Json),
            koin.get(), koin.get()), allowOverride = true)
    }

    private fun movies(range: IntRange) = range.joinToString(",") { """{"id":${910000 + it},"title":"Film $it","release_date":"2026-01-01","vote_average":7.5}""" }
    @After fun restore() {
        val koin = getKoin()
        if (::originalPrefs.isInitialized) {
            koin.declare(originalPrefs, allowOverride = true); koin.declare(originalSecrets, allowOverride = true)
            koin.declare(originalTmdb, allowOverride = true); koin.declare(originalMdb, allowOverride = true)
            koin.declare(originalLists, allowOverride = true); koin.declare(originalOmdb, allowOverride = true); koin.declare(originalEnricher, allowOverride = true)
        }
        if (::client.isInitialized) client.close()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "parity-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun click(text: String) {
        rule.waitForIdle()
        if (rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text, useUnmergedTree = true).performScrollTo().performClick()
    }
    private fun await(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }

    @Test fun actorRolesExcludeInterviewsAndRealityAndCanBeSortedAndCollapsed() {
        val original: MetadataRepository = getKoin().get()
        val credits = listOf(
            MediaItem("movie1", type = MediaType.MOVIE, title = "Acting movie", rating = 8.0, releaseDate = "2026-01-01", creditCharacter = "Hero"),
            MediaItem("tv1", type = MediaType.SERIES, title = "Acting show", rating = 9.0, releaseDate = "2026-10-01", creditCharacter = "Detective"),
            MediaItem("extra1", type = MediaType.SERIES, title = "Interview appearance", creditCharacter = "Self"),
            MediaItem("extra2", type = MediaType.SERIES, title = "Reality appearance", genreIds = listOf(10764)))
        val meta = object : MetadataRepository by original {
            override suspend fun getPersonDetail(personId: Int) = TmdbPerson(personId, "Example actor", biography = longText, knownForDepartment = "Acting")
            override suspend fun getPersonCredits(personId: Int) = credits
        }
        rule.setContent { TorveTheme { PersonScreen(42, {}, {}, remember { PersonViewModel(meta) }) } }
        await("✓ Acting roles")
        rule.onNodeWithText("Interview appearance").assertDoesNotExist()
        click("TV shows")
        rule.onNodeWithText("Acting movie").assertDoesNotExist()
        click("Appearances & extras")
        rule.onNodeWithText("Acting show").assertDoesNotExist()
        click("Highest rated")
        click("Read more")
        if (tv) {
            rule.onNodeWithText("Done").assertIsFocused()
            rule.onNodeWithText("Done").performKeyInput { pressKey(Key.DirectionDown) }
            capture("actor-biography-tv")
            rule.onNodeWithText("Done").performClick()
        } else {
            rule.onNodeWithText("Show less").performScrollTo().performClick()
            rule.onNodeWithText("Read more").assertExists()
        }
        capture("actor-filters")
    }

    @Test fun collectionsPreviewEveryPageSaveOneGroupAndRemoveWithoutWatchlistExpansion() {
        rule.setContent { TorveTheme { CollectionsScreen({}, {}, movieId = 42, collectionId = 99, collectionName = "MCU franchise") } }
        await("All character movies")
        click("All character movies")
        await("Save collection"); click("Save collection")
        await("Remove saved collection")
        assertTrue(values["saved_media_collections_v1"].orEmpty().contains("tmdb_list"))
        assertEquals(1, Json.parseToJsonElement(values.getValue("saved_media_collections_v1")).toString().count { it == '{' })
        rule.onNodeWithTag("collections.screen").performScrollToNode(hasText("Load more"))
        click("Load more")
        rule.onNodeWithTag("collections.screen").performScrollToNode(hasText("Film 24"))
        rule.onNodeWithText("Film 24").assertIsDisplayed()
        capture("collection-full-list")
        click("Remove saved collection")
        await("Save collection")
        assertEquals("[]", values["saved_media_collections_v1"])
    }

    @Test fun mdbListOwnAndSearchResultsHaveIndependentHomeVisibilityAndRemoval() {
        val koin = getKoin()
        val model = MdbListViewModel(koin.get(), koin.get(), preferences, secrets, koin.get(), SettingsRefreshNotifier())
        try {
            rule.setContent { TorveTheme { MdbListManager({}, model) } }
            await("Add My personal list"); click("Add My personal list")
            await("Hide from Home"); click("Hide from Home")
            await("Show on Home"); click("Show on Home")
            await("Hide from Home"); capture("mdblist-saved")
            if (tv) {
                rule.onNodeWithText("Hide from Home").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                rule.onNodeWithText("Hide from Home").performKeyInput { pressKey(Key.DirectionRight) }
                rule.onNodeWithText("Remove").assertIsFocused()
                rule.onNodeWithText("Remove").performKeyInput { pressKey(Key.DirectionCenter) }
            } else click("Remove")
            await("Add My personal list")
            click("Search")
            rule.onNode(hasSetTextAction() and hasText("Search lists")).performTextInput("Example")
            rule.onNode(hasText("Search lists") and !hasSetTextAction()).performClick()
            await("Add Search result list"); capture("mdblist-search")
        } finally { model.close() }
    }

    @Test fun synopsisRespectsSpoilersAndLongTextIsReadableWithoutTruncation() {
        var open by mutableStateOf(true)
        rule.setContent { TorveTheme { if (open) SynopsisDialog(ApplePlaybackSynopsis("Exact episode · S1 E2", longText, "TMDB"), false, true) { open = false } } }
        rule.waitForIdle()
        rule.onNodeWithText("Exact episode · S1 E2").assertDoesNotExist()
        rule.onNodeWithText("Reveal synopsis").performClick()
        rule.onNodeWithText("Exact episode · S1 E2").assertExists()
        val last = readableParagraphs(longText).last()
        rule.onNodeWithText(last).performScrollTo().assertIsDisplayed()
        capture("synopsis-end")
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle { assertFalse(open) }
    }

    @Test fun extensionErrorsHaveSettingsActionAndNeverBecomePlayableTitles() {
        var settings = 0
        val error = MediaItem("ai-error", title = "AI Error", type = MediaType.MOVIE,
            catalogueNotice = "The selected Gemini model is invalid.", isContentPlaceholder = true)
        rule.setContent { TorveTheme {
            com.torve.android.ui.components.PosterCard(error, { throw AssertionError("Notice opened a playable detail") })
            CatalogueNoticeHost { settings++ }
        } }
        rule.waitForIdle()
        rule.onNodeWithText("AI Error").assertDoesNotExist()
        rule.onNode(hasText("Extension needs attention", substring = true) and hasClickAction()).performClick()
        rule.onNodeWithText("Watch now").assertDoesNotExist()
        rule.onNodeWithText("The selected Gemini model is invalid.").assertIsDisplayed()
        capture("extension-notice")
        rule.onNodeWithText("Extension settings").performClick()
        rule.runOnIdle { assertEquals(1, settings); assertNull(CommunityNotices.message) }
    }
    @Test fun actualPlayerSynopsisKeepsDecoderPositionAndPauseState() {
        val original: MetadataRepository = getKoin().get()
        val metadata = object : MetadataRepository by original {
            override suspend fun getDetail(type: String, id: Int) = MediaItem("parity_playback_fixture", title = "Playback fixture", tmdbId = id,
                type = MediaType.MOVIE, overview = longText)
            override suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int) = Season(seasonNumber, episodeCount = 2, episodes = listOf(Episode(2, "Exact episode", overview = longText)))
        }
        getKoin().declare<MetadataRepository>(metadata, allowOverride = true)
        val file = File(rule.activity.cacheDir, "community-parity-playback.mp4")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/mpv_tuning_25fps.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        var visible by mutableStateOf(true)
        try {
            rule.setContent { TorveTheme { if (visible) com.torve.android.ui.player.PlayerScreen(
                url = android.net.Uri.fromFile(file).toString(), title = "Playback fixture", mediaId = "parity_playback_fixture",
                showTmdbId = 910500, onBack = { visible = false }, metadataRepo = metadata, prefsRepo = preferences) } }
            var decoder: androidx.media3.common.Player? = null
            fun find(view: android.view.View): androidx.media3.common.Player? {
                if (view is androidx.media3.ui.PlayerView) return view.player
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            rule.waitUntil(25_000) { var ready = false; rule.runOnUiThread { decoder = find(rule.activity.window.decorView); ready = decoder?.let { it.currentPosition > 600 && it.isPlaying } == true }; ready }
            val initialDecoder = decoder!!
            fun wake() {
                if (rule.onAllNodesWithText("Synopsis").fetchSemanticsNodes().isEmpty()) {
                    if (tv) rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
                    else rule.onRoot().performTouchInput { click(center) }
                }
            }
            wake()
            rule.onNodeWithText("Synopsis").performClick()
            await("Playback fixture")
            rule.waitUntil(12_000) { rule.onAllNodesWithText("A synopsis is not available for this title.").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("Done").assertExists()
            var before = 0L
            rule.runOnUiThread { before = initialDecoder.currentPosition }
            android.os.SystemClock.sleep(1100)
            rule.runOnUiThread { assertTrue(initialDecoder.currentPosition > before); assertTrue(initialDecoder.isPlaying); assertSame(initialDecoder, find(rule.activity.window.decorView)) }
            capture("player-synopsis-playing")
            rule.onNodeWithText("Done").performClick()
            wake()
            rule.onNodeWithContentDescription(rule.activity.getString(com.torve.android.R.string.common_pause)).performClick()
            rule.runOnUiThread { initialDecoder.seekTo(10_000L) }
            rule.waitUntil(5_000) { var paused = false; rule.runOnUiThread { paused = !initialDecoder.isPlaying && initialDecoder.currentPosition >= 9_500 }; paused }
            wake(); rule.onNodeWithText("Synopsis").performClick(); rule.onNodeWithText("Done").assertExists()
            rule.onNodeWithText("Done").performClick()
            rule.runOnUiThread { assertFalse(initialDecoder.isPlaying); assertTrue(initialDecoder.currentPosition in 9_500..10_500); assertSame(initialDecoder, find(rule.activity.window.decorView)) }
            capture("player-synopsis-paused")
        } finally {
            rule.runOnUiThread { visible = false }
            rule.waitForIdle()
            getKoin().declare(original, allowOverride = true)
            file.delete()
        }
    }

    @Test fun railSpacingAndPersonalBackgroundPersistAcrossContentPages() {
        val settings: SettingsViewModel = getKoin().get()
        val prior = settings.state.value.railSpacingDp
        val prefs = rule.activity.getSharedPreferences("personal_appearance_v1", 0)
        var owner = ""
        var originalPreset: String? = null
        var newProvider by mutableIntStateOf(0)
        var showCollections by mutableStateOf(false)
        try {
            rule.runOnUiThread { settings.setRailSpacingDp(0) }
            rule.setContent { TorveTheme { key(newProvider) { PersonalAppearanceProvider(settings) {
                val appearance = LocalPersonalAppearance.current
                SideEffect { if (owner.isEmpty()) { owner = appearance.owner; originalPreset = prefs.getString("$owner.preset", null) } }
                if (showCollections) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text("Background: ${appearance.preset}")
                        CollectionsScreen({}, {}, settings = settings)
                    }
                } else AppearanceControls({}, settings)
            } } } }
            click("More space")
            rule.runOnIdle { assertEquals(4, settings.state.value.railSpacingDp) }
            click("Golden glow")
            assertEquals("gold", prefs.getString("$owner.preset", null))
            capture("appearance-gold")
            rule.runOnIdle { showCollections = true; newProvider++ }
            rule.onNodeWithText("Background: gold").assertExists()
            capture("collection-background-gold")
        } finally {
            rule.runOnUiThread { if (prior == null) settings.resetRailSpacing() else settings.setRailSpacingDp(prior) }
            if (owner.isNotEmpty()) prefs.edit().also { if (originalPreset == null) it.remove("$owner.preset") else it.putString("$owner.preset", originalPreset) }.commit()
        }
    }

}
