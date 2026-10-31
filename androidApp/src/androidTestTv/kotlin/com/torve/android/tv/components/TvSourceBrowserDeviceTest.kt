package com.torve.android.tv.components

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.player.AndroidSourceTrackInspector
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.detail.SourceLanguagePicker
import com.torve.android.ui.detail.streamUiKey
import com.torve.android.ui.theme.TorveTheme
import com.torve.android.ui.theme.Charcoal
import com.torve.data.addon.*
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.mp.KoinPlatform

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class TvSourceBrowserDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    private val releases = (1..18).map {
        ParsedStream("Newznab", "1080p", "MobLand.S01E%02d.German.ML.EAC3.1080p.WEB.H264-ViDEOTHEK".format(it),
            directUrl = "https://fixture.invalid/$it.mkv", size = "2.67 GB", isCached = true)
    }

    @Test fun confirmingSortFromAScrolledListFocusesTheFirstSource() {
        val streams = releases.mapIndexed { index, stream -> stream.copy(size = "${index + 1}.0 GB") }
        rule.runOnUiThread { rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        rule.setContent {
            val scope = rememberCoroutineScope()
            val picker = remember { SourceLanguagePicker(KoinPlatform.getKoin().get<AndroidSourceTrackInspector>(), scope) }
            picker.preferredLanguage = ""; picker.streams = streams
            var sort by remember { mutableStateOf(StreamSortMode.DEFAULT) }
            TorveTheme { TvStreamBrowser("Sort regression", picker, sort, { sort = it }, false,
                { tvStreamPresentation(it) }, {}, { _, _ -> }, {}) }
        }
        rule.onNodeWithTag("tv.sources.list").performScrollToIndex(14)
        rule.onNodeWithTag("tv.source.${streams[14].streamUiKey()}")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        fun selectSort(down: Boolean) {
            rule.onNodeWithTag("tv.sources.sort").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            rule.onNodeWithTag("tv.sources.sort").performKeyInput { pressKey(Key.DirectionCenter) }
            rule.onNodeWithTag("settings.choice").assertIsDisplayed()
            if (down) rule.onNodeWithTag("settings.choice").performKeyInput { pressKey(Key.DirectionDown) }
            rule.onNodeWithTag("settings.choice").performKeyInput { pressKey(Key.DirectionCenter) }
        }
        selectSort(down = true)
        val largest = "tv.source.${streams.last().streamUiKey()}"
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(largest).assertIsFocused() }.isSuccess }
        rule.onNodeWithTag(largest).assertIsDisplayed()
        rule.onNodeWithTag("tv.sources.list").performScrollToIndex(12)
        selectSort(down = true)
        val smallest = "tv.source.${streams.first().streamUiKey()}"
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(smallest).assertIsFocused() }.isSuccess }
        rule.onNodeWithTag(smallest).assertIsDisplayed()
    }

    @Test fun usenetSettingsShowsTheNativeFormWithoutTheRetiredWizard() {
        val first = FocusRequester()
        rule.setContent {
            TorveTheme { TvSettingsStyle {
                com.torve.android.ui.settings.UsenetSettingsScreen({}, isTv = true, embedded = true, entryFocus = first)
                LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
            } }
        }
        rule.onAllNodesWithText("Panda", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Next", substring = false).assertCountEquals(0)
        rule.onNodeWithText("Enable Usenet").assertIsDisplayed()
        rule.onNodeWithText("NZB indexer 1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Download client").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("usenet.save").performScrollTo().assertIsDisplayed()
    }

    @Test fun unverifiedGermanSourcesHavePinnedControlsAndRemoteBackReturnsToOrigin() {
        val origin = FocusRequester()
        rule.setContent {
            var opened by remember { mutableStateOf(true) }
            val scope = rememberCoroutineScope()
            val picker = remember { SourceLanguagePicker(KoinPlatform.getKoin().get<AndroidSourceTrackInspector>(), scope) }
            picker.preferredLanguage = "DE"; picker.streams = releases
            TorveTheme {
                Button(onClick = { opened = true }, modifier = Modifier.focusRequester(origin).testTag("origin")) { Text("Origin") }
                if (opened) TvStreamBrowser("MobLand · S01E01", picker, StreamSortMode.DEFAULT, {}, false,
                    { tvStreamPresentation(it) }, {}, { _, _ -> }, { opened = false; origin.requestFocus() })
            }
        }
        rule.onNodeWithTag("source_language_picker").assertIsFocused()
        // The pill's corner pixels must remain the dialog background while it
        // has D-pad focus; a default rectangular indication used to fill them.
        val panel = rule.onNodeWithTag("tv.sources")
        val panelBounds = panel.fetchSemanticsNode().boundsInRoot
        val pill = rule.onNodeWithTag("source_language_picker").fetchSemanticsNode().boundsInRoot
        val bitmap = panel.captureToImage().asAndroidBitmap()
        val expected = Charcoal.toArgb()
        for (x in listOf(pill.left + 2, pill.right - 3)) {
            for (y in listOf(pill.top + 2, pill.bottom - 3)) {
                val actual = bitmap.getPixel((x - panelBounds.left).roundToInt(), (y - panelBounds.top).roundToInt())
                for (channel in listOf(16, 8, 0)) assertTrue("Focus fill escaped the rounded pill: $actual",
                    abs(((actual shr channel) and 255) - ((expected shr channel) and 255)) <= 6)
            }
        }
        rule.onNodeWithText(releases.first().title).assertIsDisplayed()
        rule.onNodeWithText("18 of 18 sources").assertIsDisplayed()
        val header = rule.onNodeWithTag("tv.sources.filters").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("tv.sources.list").performScrollToIndex(17)
        rule.onNodeWithText(releases.last().title).assertIsDisplayed()
        assertEquals(header, rule.onNodeWithTag("tv.sources.filters").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("source_language_picker").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("settings.choice").assertIsDisplayed()
        rule.onNodeWithTag("settings.choice").performKeyInput { pressKey(Key.Back) }
        rule.onNodeWithTag("source_language_picker").assertIsFocused()
        rule.onNodeWithTag("tv.sources").performKeyInput { pressKey(Key.Back) }
        rule.onNodeWithTag("tv.sources").assertDoesNotExist()
        rule.onNodeWithTag("origin").assertIsFocused()
    }

    @Test fun allSevenLanguagesKeepTheToolbarOnOneRow() {
        val language = mutableStateOf("en")
        rule.setContent {
            val base = LocalContext.current
            val config = remember(language.value) { Configuration(base.resources.configuration).apply { setLocale(Locale(language.value)) } }
            val context = remember(config) { base.createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                val scope = rememberCoroutineScope()
                val picker = remember { SourceLanguagePicker(KoinPlatform.getKoin().get<AndroidSourceTrackInspector>(), scope) }
                picker.preferredLanguage = "DE"; picker.streams = releases
                TorveTheme { TvSettingsStyle { TvStreamBrowserContent("MobLand · S01E01", picker, StreamSortMode.DEFAULT, {}, false,
                    { tvStreamPresentation(it) }, {}, { _, _ -> }) } }
            }
        }
        listOf("en", "de", "es", "fr", "it", "pt", "tr").forEach { code ->
            rule.runOnIdle { language.value = code }
            rule.waitForIdle()
            val bar = rule.onNodeWithTag("tv.sources.filters").fetchSemanticsNode().boundsInRoot
            listOf("source_language_picker", "source_provider_picker", "tv.sources.sort", "tv.sources.quality", "tv.sources.cached", "tv.sources.refresh").forEach { tag ->
                val box = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("$code $tag exceeds toolbar: $box vs $bar", box.left >= bar.left && box.right <= bar.right && box.top >= bar.top && box.bottom <= bar.bottom)
            }
            if (code == "de") {
                rule.onNodeWithTag("tv.sources").captureToImage().asAndroidBitmap().let { bitmap ->
                    File(rule.activity.cacheDir, "source-browser-german.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                rule.onNodeWithText("Qualität").assertIsDisplayed()
                rule.onAllNodesWithText("1080p · Deutsch · 2.67 GB").onFirst().assertIsDisplayed()
            }
        }
    }
}
