package com.torve.android.tv.screens

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.replays.*
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.replays.ReplayHighlight
import com.torve.presentation.settings.ThemeMode
import java.io.File
import java.io.FileInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReplayTvFullscreenDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<TorveTestHostActivity>()
    private val video = ReplayHighlight("gtfEW_f1-b8", "Game highlights", "EuroLeague", 590)

    @Test fun officialYouTubeIsTriedWhenSmartTubeIsMissingAndBrowsersAreExcluded() {
        val attempts = mutableListOf<Intent>()
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                attempts += intent
                if (intent.`package` != "com.amazon.firetv.youtube") throw ActivityNotFoundException()
            }
        }
        assertTrue(launchReplayTvYouTube(context, video.id))
        assertEquals("com.amazon.firetv.youtube", attempts.last().`package`)
        assertTrue(attempts.all { it.`package` in setOf("org.smarttube.stable", "org.smarttube.beta", "com.amazon.firetv.youtube") })
        assertTrue(attempts.all { it.data?.getQueryParameter("v") == video.id && it.getBooleanExtra("finish_on_ended", false) })
        assertTrue(attempts.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        assertFalse(replayTvYouTubeIntents(video.id, "com.amazon.cloud9").any { it.`package` == "com.amazon.cloud9" })
        assertTrue(replayTvYouTubeIntents("not-a-video-link").isEmpty())
    }

    @Test fun missingTvPlayerShowsAnExplanationWithoutCreatingAnEmbeddedVideo() {
        var embeddedViewCreated = false
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { TorveTheme(ThemeMode.DARK) {
            var open by remember { mutableStateOf(true) }
            if (open) ReplayHighlightPlayer(video, { open = false }, {}, onViewReady = { embeddedViewCreated = true }, isTv = true)
            else Text("Returned from missing player")
        } } }
        compose.onNodeWithTag("highlights.player.missing").assertIsDisplayed()
        compose.onAllNodesWithTag("highlights.player").assertCountEquals(0)
        assertFalse(embeddedViewCreated)
        compose.onNodeWithTag("highlights.player.close").performClick()
        compose.onNodeWithText("Returned from missing player").assertIsDisplayed()
    }

    @Test fun highlightOpensAFullscreenTvPlayerAndRemotePausePlayAndBackWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        var embeddedViewCreated = false
        compose.setContent { TorveTheme(ThemeMode.DARK) {
            var open by remember { mutableStateOf(true) }
            if (open) ReplayHighlightPlayer(video, { open = false }, {}, onViewReady = { embeddedViewCreated = true })
            else Text("Returned from fullscreen highlight")
        } }
        fun await(seconds: Long, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + seconds * 1000
            while (!predicate()) {
                if (SystemClock.elapsedRealtime() >= deadline) fail("Fullscreen player condition timed out")
                SystemClock.sleep(200)
            }
        }
        val packages = setOf("org.smarttube.stable", "org.smarttube.beta", "com.amazon.firetv.youtube", "com.google.android.youtube.tv")
        await(20) { automation.rootInActiveWindow?.packageName?.toString() in packages }
        val player = checkNotNull(automation.rootInActiveWindow?.packageName?.toString())
        fun press(code: Int) {
            val now = SystemClock.uptimeMillis()
            assertTrue(automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true))
            assertTrue(automation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true))
        }
        val bounds = Rect().also { checkNotNull(automation.rootInActiveWindow).getBoundsInScreen(it) }
        val metrics = compose.activity.resources.displayMetrics
        assertTrue("TV player does not fill the screen: $bounds", bounds.width() >= metrics.widthPixels * .95 && bounds.height() >= metrics.heightPixels * .95)
        fun media(): Pair<Int, Long>? {
            val dump = automation.executeShellCommand("dumpsys media_session").use { fd ->
                FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
            }
            val block = dump.substringAfter("package=$player", "").substringBefore("\n      package=")
            val state = Regex("state=PlaybackState \\{state=(\\d+), position=(\\d+)").find(block) ?: return null
            return state.groupValues[1].toInt() to state.groupValues[2].toLong()
        }
        try {
            await(75) { media()?.first == 3 }
            val started = checkNotNull(media()).second
            SystemClock.sleep(3500)
            press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            await(10) { media()?.first == 2 }
            val paused = checkNotNull(media()).second
            assertTrue("The actual media clock did not advance", paused >= started + 2000)
            press(KeyEvent.KEYCODE_DPAD_UP)
            SystemClock.sleep(500)
            automation.takeScreenshot().let { bitmap ->
                File(compose.activity.getExternalFilesDir(null), "fullscreen-highlight-controls.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            press(KeyEvent.KEYCODE_MEDIA_PLAY)
            await(10) { media()?.first == 3 }
            assertFalse("A TV highlight created an embedded WebView", embeddedViewCreated)
            File(compose.activity.getExternalFilesDir(null), "fullscreen-highlight-observation.json").writeText(JSONObject()
                .put("playerPackage", player).put("bounds", bounds.toShortString()).put("pauseObserved", true)
                .put("resumeObserved", true).put("mediaClockAdvancedMs", paused-started)
                .put("pausedPositionMs", paused).put("embeddedViewCreated", embeddedViewCreated).toString())
        } finally {
            press(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(1200)
            if (automation.rootInActiveWindow?.packageName?.toString() in packages) press(KeyEvent.KEYCODE_BACK)
        }
        compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.toString() == compose.activity.packageName }
        compose.onNodeWithText("Returned from fullscreen highlight").assertIsDisplayed()
    }
}
