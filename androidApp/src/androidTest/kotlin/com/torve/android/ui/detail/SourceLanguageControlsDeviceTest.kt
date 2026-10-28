package com.torve.android.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.torve.android.player.AndroidSourceTrackInspector
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.ui.theme.TorveTheme
import com.torve.data.addon.ParsedStream
import org.junit.Rule
import org.junit.Test
import org.koin.mp.KoinPlatform

class SourceLanguageControlsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun sourceAndLanguageMenusKeepAudioSeparateFromOptionalSubtitles() {
        lateinit var picker: SourceLanguagePicker
        rule.setContent {
            val scope = rememberCoroutineScope()
            picker = androidx.compose.runtime.remember(scope) {
                SourceLanguagePicker(KoinPlatform.getKoin().get<AndroidSourceTrackInspector>(), scope)
            }
            picker.preferredLanguage = "DE"
            picker.streams = listOf(
                ParsedStream("Torrentio [RD+]", "1080p", "German audio", directUrl = "https://fixture.example/movie.mp4",
                    languages = listOf("DE"), trackLanguagesVerified = true),
                ParsedStream("Panda Usenet TorBox", "1080p", "English audio German subtitles", directUrl = "https://fixture.example/nzb/123",
                    languages = listOf("EN"), subtitleLanguages = listOf("DE"), trackLanguagesVerified = true),
            )
            TorveTheme {
                Column(Modifier.width(360.dp)) {
                    SourceLanguageControls(picker)
                    picker.visible.forEach { Text(it.title) }
                }
            }
        }
        rule.onNodeWithText("German audio").assertIsDisplayed()
        rule.onNodeWithText("English audio German subtitles").assertDoesNotExist()
        rule.onNodeWithTag("source_provider_picker").performClick()
        rule.onNodeWithTag("source_provider_USENET").performClick()
        rule.onNodeWithText("German audio").assertDoesNotExist()
        rule.runOnIdle { picker.allowSubtitles = true }
        rule.onNodeWithText("English audio German subtitles").assertIsDisplayed()
        rule.runOnIdle { picker.allowSubtitles = false }
        rule.onNodeWithTag("source_language_picker").performClick()
        rule.onNodeWithTag("source_language_").performClick()
        rule.onNodeWithText("English audio German subtitles").assertIsDisplayed()
        rule.onNodeWithTag("source_provider_picker").performClick()
        rule.onNodeWithTag("source_provider_REAL_DEBRID").performClick()
        rule.onNodeWithText("German audio").assertIsDisplayed()
        rule.onNodeWithText("English audio German subtitles").assertDoesNotExist()
    }
}
