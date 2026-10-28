package com.torve.android.tv.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.player.AndroidSourceTrackInspector
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.detail.SourceLanguageControls
import com.torve.android.ui.detail.SourceLanguagePicker
import com.torve.android.ui.theme.*
import com.torve.data.addon.ParsedStream
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.koin.mp.KoinPlatform

class TvCompactSourcesDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()
    @Test fun focusedSourceShowsReadableAudioAndCompactControlsWithoutSubtitleInventory() {
        val first = FocusRequester()
        val stream = ParsedStream("Panda", "4K", "The.Gentlemen.S02E02.GERMAN.DL.2160p",
            languages = listOf("DE", "EN"), subtitleLanguages = listOf("DE", "FR", "JA", "ES"),
            source = "videothek.io+torbox · 💾 8.3 GB 🗣 German,English 💬 German,French,Japanese,Spanish",
            size = "8.3 GB", isCached = true)
        rule.setContent {
            val scope = rememberCoroutineScope()
            val picker = remember { SourceLanguagePicker(KoinPlatform.getKoin().get<AndroidSourceTrackInspector>(), scope) }
            picker.preferredLanguage = "DE"
            picker.streams = listOf(stream)
            TorveTheme {
                Column(Modifier.width(900.dp).background(Obsidian).padding(24.dp).testTag("sources.capture"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SourceLanguageControls(picker, textColor = Snow)
                    TvStreamResultRow(tvStreamPresentation(stream),
                        modifier = Modifier.focusRequester(first).testTag("source.row"), onClick = {})
                }
                LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        rule.onNodeWithTag("source.row").assertIsFocused()
        rule.onAllNodesWithText("Subtitles", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("French", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("German", substring = true).onFirst().assertIsDisplayed()
        val language = rule.onNodeWithTag("source_language_picker").fetchSemanticsNode().boundsInRoot
        val provider = rule.onNodeWithTag("source_provider_picker").fetchSemanticsNode().boundsInRoot
        assertTrue("Related filters must remain adjacent", provider.left - language.right < 48f)
        rule.onNodeWithTag("sources.capture").captureToImage().asAndroidBitmap().let { bitmap ->
            File(rule.activity.cacheDir, "compact-tv-sources.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
