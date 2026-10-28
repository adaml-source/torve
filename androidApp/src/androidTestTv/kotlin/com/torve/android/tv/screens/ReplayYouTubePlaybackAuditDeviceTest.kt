package com.torve.android.tv.screens

import android.webkit.WebView
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.replays.ReplayHighlightPlayer
import com.torve.android.ui.replays.ReplayYouTubeBrowser
import com.torve.android.ui.replays.ReplayYouTubeFailure
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.network.HttpClientFactory
import com.torve.data.replays.*
import com.torve.presentation.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Playback success requires an advancing YouTube media clock, not just an open dialog. */
class ReplayYouTubePlaybackAuditDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()
    private fun screenshot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
    private fun advancing(web: AtomicReference<WebView?>, timeout: Long = 45_000, minimumSeconds: Float = 2f): Float {
        val seconds = AtomicReference(-1f)
        var nextCheck = 0L
        compose.waitUntil(timeout) {
            val now = android.os.SystemClock.elapsedRealtime()
            val view = web.get()
            if (view != null && now >= nextCheck) {
                nextCheck = now + 500
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    view.evaluateJavascript("(function(){if(document.querySelector('.ad-showing'))return -1;if(window.player&&typeof window.player.getCurrentTime==='function')return window.player.getCurrentTime();var v=document.querySelector('video');return v&&!v.paused?v.currentTime:-1;})()") {
                        seconds.set(it.toFloatOrNull() ?: -1f)
                    }
                }
            }
            seconds.get() >= minimumSeconds
        }
        return seconds.get()
    }
    @Test fun officialHighlightReallyPlaysAndBackReturnsToTheChooser() {
        val web = AtomicReference<WebView?>(null)
        var handedOff = false
        compose.setContent { TorveTheme(ThemeMode.DARK) {
            var open by remember { mutableStateOf(true) }
            if (open) ReplayHighlightPlayer(ReplayHighlight("gtfEW_f1-b8", "Game highlights", "EuroLeague", 590),
                onDismiss = { open = false }, onOpenExternal = { handedOff = true }, onViewReady = { web.set(it) }, isTv = false)
            else Text("Returned to highlights")
        } }
        try {
            val time = advancing(web, minimumSeconds = 10f)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("highlights.player.playing").assertIsDisplayed() }.isSuccess
            }
            val geometry = AtomicReference<String?>(null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = checkNotNull(web.get())
                view.evaluateJavascript("JSON.stringify({width:innerWidth,height:innerHeight,visible:document.visibilityState,iframe:player.getIframe().getBoundingClientRect().toJSON(),state:player.getPlayerState()})") {
                    geometry.set("{\"nativeWidth\":${view.width},\"nativeHeight\":${view.height},\"shown\":${view.isShown},\"document\":$it}")
                }
            }
            compose.waitUntil(3_000) { geometry.get() != null }
            java.io.File(compose.activity.getExternalFilesDir(null), "youtube-embed-geometry.json").writeText(checkNotNull(geometry.get()))
            val document = org.json.JSONObject(org.json.JSONObject(checkNotNull(geometry.get())).getString("document"))
            assertTrue("The actual YouTube iframe must fill the visible viewport",
                document.getJSONObject("iframe").getDouble("height") >= document.getDouble("height") * .95)
            java.io.File(compose.activity.getExternalFilesDir(null), "youtube-embed-observation.json")
                .writeText("{\"mediaSeconds\":$time,\"externalAppOpened\":$handedOff}")
            screenshot("youtube-highlight-playing")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("Returned to highlights").assertIsDisplayed()
            assertFalse(handedOff)
        } catch (failure: Throwable) {
            screenshot("youtube-embed-failure")
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            java.io.File(compose.activity.getExternalFilesDir(null), "youtube-embed-failure-semantics.txt")
                .writeText(roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() })
            throw failure
        }
    }
    @Test fun ownerRestrictionIsExplainedAndWatchPageRequiresAnExplicitChoice() {
        var watch = false
        compose.setContent { TorveTheme(ThemeMode.DARK) {
            ReplayYouTubeFailure(150, {}, { watch = true }, {})
        } }
        compose.onNodeWithText("This channel requires YouTube playback").assertIsDisplayed()
        assertFalse(watch)
        compose.onNodeWithText("YouTube’s page may show scores or results").assertIsDisplayed()
        compose.onNodeWithTag("highlights.player.watch").performClick()
        assertTrue(watch)
    }
    @Test fun reportedFormulaOneHighlightPlaysEmbeddedOrOnItsOfficialPageInsideTorve() {
        val repository = WebReplayRepository(HttpClientFactory.create())
        val highlights = try { runBlocking { repository.highlights(ReplayEvent("qa-usgp", "qa",
            "RACE - F1 2026 - United States Grand Prix - Full Race Replay - October 25, 2026 - Formula 1",
            "https://example.invalid/replay", sport = "Motorsport", competition = "Formula 1", date = "2026-10-25"), null) } }
            finally { repository.close() }
        val video = highlights.firstOrNull { it.isOfficial } ?: error("The reported official race highlight was not found")
        val embeddedView = AtomicReference<WebView?>(null)
        val watchView = AtomicReference<WebView?>(null)
        var usingWatch by mutableStateOf(false)
        var closed by mutableStateOf(false)
        compose.setContent { TorveTheme(ThemeMode.DARK) {
            if (closed) Text("Returned from race highlights")
            else if (usingWatch) ReplayYouTubeBrowser(video.url, onViewReady = { watchView.set(it) }) { closed = true }
            else ReplayHighlightPlayer(video, onDismiss = { closed = true },
                onOpenExternal = { usingWatch = true }, onViewReady = { embeddedView.set(it) }, isTv = false)
        } }
        try {
            compose.waitUntil(25_000) {
                compose.onAllNodesWithTag("highlights.player.playing").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("highlights.player.watch").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithTag("highlights.player.watch").fetchSemanticsNodes().isNotEmpty()) {
                screenshot("f1-player-restriction")
                compose.onNodeWithTag("highlights.player.watch").performClick()
            }
            // The official watch page may show a normal preroll. Do not count ad
            // playback as success, and leave its controls/ad behaviour untouched.
            val time = advancing(if (usingWatch) watchView else embeddedView, if (usingWatch) 120_000 else 45_000)
            java.io.File(compose.activity.getExternalFilesDir(null), "f1-highlight-observation.json")
                .writeText("{\"videoId\":\"${video.id}\",\"official\":true,\"insideYouTubePage\":$usingWatch,\"mediaSeconds\":$time,\"externalAppOpened\":false}")
            screenshot("f1-highlight-playing")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("Returned from race highlights").assertIsDisplayed()
        } catch (failure: Throwable) { screenshot("f1-highlight-failure"); throw failure }
    }
}
