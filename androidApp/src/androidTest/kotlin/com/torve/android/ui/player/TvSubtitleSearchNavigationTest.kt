package com.torve.android.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.torve.android.test.TorveTestHostActivity
import com.torve.data.subtitles.SubtitleMatchTier
import androidx.test.espresso.Espresso
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvSubtitleSearchNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun smartMatchReceivesInitialFocusAndDpadReachesFilters() {
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(
                    state = results(),
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("Smart Match").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Smart Match").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("Strong only").assertIsFocused()
    }

    @Test
    fun backAlwaysDismissesSubtitleBrowser() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(
                    state = results(),
                    onSelect = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        Espresso.pressBack()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    private fun results() = SubtitleFetchState.Results(
        subtitles = listOf(
            SubtitleCandidate(
                flagEmoji = "EN",
                languageName = "English",
                languageCode = "en",
                displayLabel = "Show.S01E02.1080p.WEB-DL-GROUP.srt",
                releaseName = "Show.S01E02.1080p.WEB-DL-GROUP",
                provider = "OpenSubtitles.com",
                ratings = 9.4,
                voteCount = 500,
                downloadCount = 42_318,
                fromTrusted = true,
                matchTier = SubtitleMatchTier.EXACT_RELEASE,
                matchScore = 98,
                qualityScore = 91,
                rankingReasons = listOf("+68 Normalized exact release"),
            ),
        ),
        matchingRelease = "Show.S01E02.1080p.WEB-DL-GROUP",
        movieHashAvailable = false,
        hasStrongMatch = true,
    )
}
