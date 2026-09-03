package com.torve.android.ui.player

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.torve.android.test.TorveTestHostActivity
import com.torve.data.subtitles.SubtitleMatchTier
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
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
                    onLoadMore = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("✓ Smart Match").assertExists()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("✓ Smart Match").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("Search more").assertIsFocused()
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun backAlwaysDismissesSubtitleBrowser() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(
                    state = results(),
                    onSelect = {},
                    onLoadMore = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithText("✓ Smart Match").assertExists()
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun dpadCanRequestAdditionalProviderPages() {
        var loadMoreRequested = false
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(
                    state = results(),
                    onSelect = {},
                    onLoadMore = { loadMoreRequested = true },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("✓ Smart Match").assertExists()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("✓ Smart Match").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithText("Search more").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.waitUntil(timeoutMillis = 5_000) { loadMoreRequested }
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
        providerStatus = "OpenSubtitles.com: Loaded 1 of 25 · Subtitle addons: Not configured",
        openSubtitlesPageLimit = 2,
        canLoadMore = true,
    )

    private fun pressRemoteKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        composeRule.waitForIdle()
    }
}
