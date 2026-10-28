package com.torve.android.ui.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.catalog.SportsIndexer
import com.torve.android.catalog.sportsPageKey
import com.torve.android.tv.screens.sportsStableId
import com.torve.android.tv.screens.SPORTS_FILTER_RECENT
import com.torve.android.tv.screens.TvSportsScreen
import com.torve.android.tv.screens.tvSportsRefreshPlan
import com.torve.data.panda.NzbIndexerRow
import com.torve.data.usenet.NewznabClient
import com.torve.data.usenet.NewznabItem
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.sports.SportBucket
import com.torve.presentation.panda.PandaConfigStateStore
import com.torve.presentation.panda.PandaSetupUiState
import com.torve.presentation.usenet.NzbBrowseStateHolder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvSportsNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()
    private var http: HttpClient? = null
    private var pageKey = ""
    private val queriedIndexers = ConcurrentHashMap.newKeySet<String>()
    private val today = LocalDate.now()

    @Before fun keepFixtureVisible() {
        composeRule.activityRule.scenario.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private val releases = (0 until 30).map { index ->
        NewznabItem(title = if (index == 0) "Euroleague.$today.Bayern.M%C3%BCnchen.vs.Fenerbah&ccedil;e.1080p.WEB-GRP"
            else "Euroleague.$today.Team.$index.vs.Opponent.1080p.WEB-GRP",
            guid = "event-$index", nzbUrl = "https://indexer.example/event-$index")
    } + listOf(
        NewznabItem("Euroleague.$today.Bayern.M.C3.BCnchen.vs..C5.BDalgiris.1080p", "https://indexer.example/escaped", "escaped"),
        NewznabItem("Euroleague.${today.minusDays(1)}.Yesterday.One.vs.Two.1080p", "https://indexer.example/y1", "y1"),
        NewznabItem("Euroleague.${today.minusDays(1)}.Yesterday.Three.vs.Four.1080p", "https://indexer.example/y2", "y2"),
        NewznabItem("NFL.$today.One.vs.Two.1080p", "https://indexer.example/nfl", "nfl"),
        NewznabItem("Tanked.S03E04.NBA.Star.1080p", "https://indexer.example/noise", "noise"),
        NewznabItem("NBA.2K27.PS5", "https://indexer.example/game", "game", categoryId = "5060"),
        NewznabItem("NBA.Documentary.$today.1080p", "https://indexer.example/documentary", "documentary", categoryId = "5060"),
        NewznabItem("Euroleague.$today.One.vs.Two.1080p", "https://indexer.example/wrong-category", "wrong-category", categoryId = "2040"),
    )

    @After fun closeClient() {
        composeRule.waitUntil(10_000) { NzbBrowseStateHolder.get(pageKey).activeRefreshScopes.isEmpty() }
        http?.close()
        NzbBrowseStateHolder.clear(pageKey)
    }

    private fun assertSportsTextDisplayed(text: String) {
        try {
            composeRule.onNodeWithText(text).assertIsDisplayed()
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}\n${composeRule.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
    }

    private fun remotePress(code: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        automation.injectInputEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0), true)
        automation.injectInputEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true)
        composeRule.waitForIdle()
    }

    private fun openSports(stale: Boolean = false, multiIndexer: Boolean = false, firstFails: Boolean = false) {
        val indexers = listOf(SportsIndexer("custom", "https://indexer.example", "test-key")) +
            if (multiIndexer) listOf(SportsIndexer("custom", "https://second.example", "second-key")) else emptyList()
        pageKey = sportsPageKey(indexers)
        val plan = tvSportsRefreshPlan(SportBucket.BASKETBALL.name, "")
        NzbBrowseStateHolder.put(pageKey, NzbBrowseStateHolder.State(
            items = if (stale) listOf(NewznabItem("Euroleague.${today.minusMonths(3)}.Old.Game",
                "https://indexer.example/old", "old")) else releases,
            selectedSportBucket = SportBucket.BASKETBALL.name,
            selectedSportsDateMode = SPORTS_FILTER_RECENT,
            sportsRefreshedAtByScope = mapOf(plan.scopeId to if (stale) 0L else System.currentTimeMillis()),
        ))
        val client = HttpClient(MockEngine { request ->
            queriedIndexers += request.url.host
            if (firstFails && request.url.host == "indexer.example") {
                return@MockEngine respond("quota exceeded", HttpStatusCode.TooManyRequests)
            }
            val query = request.url.parameters["q"].orEmpty()
            val candidates = releases.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            val offset = request.url.parameters["offset"]!!.toInt()
            val limit = request.url.parameters["limit"]!!.toInt()
            val xml = candidates.drop(offset).take(limit).joinToString("") {
                "<item><title>${it.title}</title><guid>${it.guid}</guid><link>${it.nzbUrl.replace("indexer.example", request.url.host)}</link>" +
                    (it.categoryId?.let { category -> "<newznab:attr name=\"category\" value=\"$category\"/>" } ?: "") + "</item>"
            }
            respond("<rss xmlns:newznab=\"http://www.newznab.com/DTD/2010/feeds/attributes/\"><channel>$xml</channel></rss>")
        })
        http = client
        val prefs = object : PreferencesRepository {
            private val values = mutableMapOf<String, String>()
            override suspend fun getString(key: String) = values[key]
            override suspend fun setString(key: String, value: String) { values[key] = value }
            override suspend fun remove(key: String) { values.remove(key) }
        }
        val local = object : DeviceLocalSettingsRepository {
            override suspend fun getString(key: String): String? = null
            override suspend fun setString(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val secrets = object : IntegrationSecretStore {
            override suspend fun put(key: IntegrationSecretKey, value: String, subKey: String?) = Unit
            override suspend fun get(key: IntegrationSecretKey, subKey: String?): String? = null
            override suspend fun remove(key: IntegrationSecretKey, subKey: String?) = Unit
            override suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode) = Unit
            override suspend fun getStorageMode(key: IntegrationSecretKey) = IntegrationStorageMode.DEVICE_ONLY
            override suspend fun clearAllSecrets() = Unit
        }
        val store = PandaConfigStateStore().apply {
            publish(PandaSetupUiState(nzbIndexers = indexers.map { NzbIndexerRow(it.type, it.url, it.apiKey) }))
        }
        composeRule.setContent {
            MaterialTheme {
                val rail = remember { FocusRequester() }
                var railFocused by remember { mutableStateOf(false) }
                var first by remember { mutableStateOf<FocusRequester?>(null) }
                BackHandler(enabled = !railFocused) { rail.requestFocus() }
                LaunchedEffect(first) {
                    withFrameNanos { }
                    first?.requestFocus()
                }
                Row(Modifier.fillMaxSize()) {
                    Text("Main menu", modifier = Modifier.width(100.dp).focusRequester(rail)
                        .onFocusChanged { if (it.isFocused) railFocused = true }.clickable {})
                    Box(Modifier.weight(1f)) {
                        TvSportsScreen(
                            railFocusRequester = rail,
                            onPlayStream = { _, _, _ -> error("Navigation tests do not start playback") },
                            onFirstContentRequester = { first = it },
                            onContentFocused = { railFocused = false },
                            pandaStore = store,
                            newznab = NewznabClient(client),
                            prefsRepo = prefs,
                            localSettingsRepo = local,
                            secretStore = secrets,
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(10_000) {
            NzbBrowseStateHolder.get(pageKey).items.any { it.guid == "event-0" }
        }
        composeRule.waitForIdle()
    }

    private fun eventTag(guid: String, host: String = "indexer.example") = "sports_event_" +
        releases.first { it.guid == guid }.let { it.copy(nzbUrl = it.nzbUrl.replace("indexer.example", host)) }.sportsStableId()

    @Test fun sportsCombinesBothIndexersAndPreservesTheirDownloadLinks() {
        openSports(stale = true, multiIndexer = true)
        composeRule.waitUntil(10_000) { NzbBrowseStateHolder.get(pageKey).activeRefreshScopes.isEmpty() }
        val items = NzbBrowseStateHolder.get(pageKey).items
        assertTrue(queriedIndexers.containsAll(listOf("indexer.example", "second.example")))
        assertTrue(items.map { URI(it.nzbUrl).host }.toSet() == setOf("indexer.example", "second.example"))
        assertTrue(items.size == items.map { it.sportsStableId() }.distinct().size)
        assertTrue("Unexpected non-event: ${items.filter { it.guid in setOf("game", "documentary", "wrong-category", "noise") }.map { it.guid }}",
            items.none { it.guid in setOf("game", "documentary", "wrong-category", "noise") })
        composeRule.onNodeWithText("2 indexers", substring = true).assertIsDisplayed()
        saveSportsCapture("sports-all-indexers.png")
    }

    @Test fun sportsKeepsResultsVisibleWhenFirstIndexerHitsQuota() {
        openSports(stale = true, multiIndexer = true, firstFails = true)
        composeRule.waitUntil(10_000) { NzbBrowseStateHolder.get(pageKey).activeRefreshScopes.isEmpty() }
        val state = NzbBrowseStateHolder.get(pageKey)
        assertTrue(queriedIndexers.containsAll(listOf("indexer.example", "second.example")))
        assertTrue(state.items.isNotEmpty() && state.items.all { URI(it.nzbUrl).host == "second.example" })
        assertTrue(state.refreshErrorsByScope.values.any { it.contains("indexer.example") && it.contains("HTTP 429") })
        composeRule.onNodeWithTag("tv_sports_events").assertIsDisplayed()
        composeRule.onNodeWithText("Some indexers unavailable", substring = true).assertIsDisplayed()
        saveSportsCapture("sports-one-indexer-unavailable.png")
    }

    @Test fun sportsExcludesGamesShowsAndMovieCategoriesBeforeRenderingOrCaching() {
        openSports(stale = true, multiIndexer = true)
        composeRule.waitUntil(10_000) { NzbBrowseStateHolder.get(pageKey).activeRefreshScopes.isEmpty() }
        val items = NzbBrowseStateHolder.get(pageKey).items
        assertTrue(items.any { it.guid == "event-0" })
        assertTrue("Unexpected non-event: ${items.filter { it.guid in setOf("game", "documentary", "wrong-category", "noise") }.map { it.guid }}",
            items.none { it.guid in setOf("game", "documentary", "wrong-category", "noise") })
        composeRule.onNodeWithTag("tv_sports_events").assertIsDisplayed()
        saveSportsCapture("sports-166-event-filter.png")
    }

    private fun saveSportsCapture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = java.io.File(composeRule.activity.getExternalFilesDir(null), name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun backFromDeepResultGoesToFiltersThenMainMenuAndDownRetainsPosition() {
        openSports()
        composeRule.onNodeWithTag("tv_sports_events").performScrollToNode(hasTestTag(eventTag("event-18")))
        composeRule.onNodeWithTag(eventTag("event-18")).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        remotePress(KeyEvent.KEYCODE_BACK)
        val basketball = composeRule.onNode(hasText("Basketball · 33") and hasClickAction())
        basketball.assertIsFocused()
        basketball.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(eventTag("event-18")).assertIsFocused()
        remotePress(KeyEvent.KEYCODE_BACK)
        basketball.assertIsFocused()
        remotePress(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithText("Main menu").assertIsFocused()
    }

    @Test fun datePopupKeepsFocusAcrossNativeBackAndSelectionKeepsBasketball() {
        openSports()
        repeat(2) {
            composeRule.onNodeWithTag("tv_sports_dates").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            remotePress(KeyEvent.KEYCODE_DPAD_CENTER)
            composeRule.onNodeWithText("Sports dates").assertIsDisplayed()
            composeRule.onNodeWithText("‹ Older week").assertIsDisplayed()
            remotePress(KeyEvent.KEYCODE_BACK)
            composeRule.waitUntil(5_000) { composeRule.activity.hasWindowFocus() }
            composeRule.onNodeWithTag("tv_sports_dates").assertIsFocused()
        }
        remotePress(KeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.onNode(hasText("Yesterday") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) { composeRule.activity.hasWindowFocus() }
        composeRule.onNodeWithTag("tv_sports_dates").assertIsFocused()
        composeRule.waitUntil(5_000) { NzbBrowseStateHolder.get(pageKey).activeRefreshScopes.isEmpty() }
        assertSportsTextDisplayed("Basketball · Yesterday · 2 results")
        assertTrue(NzbBrowseStateHolder.get(pageKey).selectedSportBucket == SportBucket.BASKETBALL.name)
    }

    @Test fun staleFeedRefreshesAutomaticallyWithoutReclassifyingTankedAsBasketball() {
        openSports(stale = true)
        composeRule.onNodeWithTag("sports_event_event-0").assertIsDisplayed()
        assertTrue(NzbBrowseStateHolder.get(pageKey).items.none { it.guid == "noise" || it.guid == "old" })
    }

    @Test fun cachedEncodedTeamNamesRenderAsUnicodeInTheActualSportsList() {
        openSports()
        assertSportsTextDisplayed("Bayern München vs Fenerbahçe")
        assertTrue(NzbBrowseStateHolder.get(pageKey).items.first().title.contains("%C3%BC"))
    }

    @Test fun cachedSeparatorEscapedTeamNamesRenderAsUnicodeInTheActualSportsList() {
        openSports()
        composeRule.onNodeWithTag("tv_sports_events").performScrollToNode(hasTestTag(eventTag("escaped")))
        assertSportsTextDisplayed("Bayern München vs Žalgiris")
        assertTrue(NzbBrowseStateHolder.get(pageKey).items.any { it.title.contains("M.C3.BC") })
    }
}
