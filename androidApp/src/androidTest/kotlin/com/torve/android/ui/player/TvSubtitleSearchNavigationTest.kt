package com.torve.android.ui.player

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.torve.android.test.TorveTestHostActivity
import com.torve.data.subtitles.SubtitleMatchTier
import com.torve.data.subtitles.SubtitleMatchQuality
import com.torve.data.subtitles.SubtitleEvidence
import com.torve.data.subtitles.SubtitleEvidenceState
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
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Smart Match", substring = true).assertIsFocused()
                true
            }.getOrDefault(false) ||
            runCatching {
                composeRule.onNodeWithText("✓ Smart Match").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Search more").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Strong only").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("Search more")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("Smart Match", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("All languages", substring = true).assertIsFocused()
                true
            }.getOrDefault(false)
        }
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("German", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("English", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("German", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("All languages", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("EN  English")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocused("All languages", substring = true)
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
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("✓ Smart Match").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun dpadSkipsDisabledSearchMoreFilter() {
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(
                    state = results(canLoadMore = false),
                    onSelect = {},
                    onLoadMore = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Smart Match", substring = true)
                .fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Focused) == true } ||
            runCatching {
                composeRule.onNodeWithText("Smart Match", substring = true).assertIsFocused()
                true
            }.getOrDefault(false) ||
            runCatching {
                composeRule.onNodeWithText("âœ“ Smart Match").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Strong only").assertIsFocused()
                true
            }.getOrDefault(false)
        }
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
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("✓ Smart Match").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Search more").assertIsFocused()
                true
            }.getOrDefault(false)
        }
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.waitUntil(timeoutMillis = 5_000) { loadMoreRequested }
    }

    @Test
    fun filtersLanguagesAndResultsHaveReversibleDpadPaths() {
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(results(), {}, {}, {})
            }
        }
        awaitFocused("Smart Match", substring = true)

        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("Search more")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("Strong only")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("Search more")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("Smart Match", substring = true)

        pressRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("All languages", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("EN  English")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("DE  German")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocused("EN  English")
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocused("All languages", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocused("Smart Match", substring = true)
    }

    @Test
    fun preferredLanguageChipReceivesInitialFocusWithoutSelectPress() {
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(
                    state = results(),
                    onSelect = {},
                    onLoadMore = {},
                    onDismiss = {},
                    preferredLanguage = "English",
                )
            }
        }

        awaitFocused("English", substring = true)
    }

    @Test
    fun asyncAppendRetainsFocusedResult() {
        val uiState = androidx.compose.runtime.mutableStateOf<SubtitleFetchState>(results())
        composeRule.setContent {
            MaterialTheme {
                TvSubtitleSearchOverlay(uiState.value, {}, {}, {})
            }
        }
        awaitFocused("Smart Match", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("All languages", substring = true)
        pressRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("EN  English")

        composeRule.runOnIdle {
            val current = uiState.value as SubtitleFetchState.Results
            uiState.value = current.copy(
                subtitles = current.subtitles + current.subtitles.first().copy(
                    flagEmoji = "FR",
                    languageName = "French",
                    languageCode = "fr",
                    displayLabel = "Show.S01E02.1080p.WEB-DL-GROUP.fr.srt",
                    directUrl = "https://subtitle.example/fr.srt",
                ),
            )
        }

        awaitFocused("EN  English")
    }

    @Test
    fun rawProviderIdsAreNeverRenderedAsUserFacingMetadata() {
        val numeric = results().let { base ->
            base.copy(
                subtitles = listOf(
                    base.subtitles.first().copy(
                        displayLabel = "3279335",
                        releaseName = "3279335",
                        osFileId = 3279335,
                        matchQuality = SubtitleMatchQuality.POSSIBLE,
                        matchExplanation = "Correct episode · release match unknown",
                    ),
                ),
            )
        }
        composeRule.setContent {
            MaterialTheme { TvSubtitleSearchOverlay(numeric, {}, {}, {}) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("3279335", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Correct episode · release match unknown").assertExists()
    }

    private fun results(canLoadMore: Boolean = true) = SubtitleFetchState.Results(
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
                contentIdentityScore = 100,
                releaseMatchScore = 100,
                syncConfidenceScore = 98,
                matchQuality = SubtitleMatchQuality.BEST,
                matchExplanation = "Exact release match",
                evidence = listOf(
                    SubtitleEvidence(SubtitleEvidenceState.MATCH, "Exact S01E02"),
                    SubtitleEvidence(SubtitleEvidenceState.MATCH, "Exact release name"),
                ),
                sourceType = "web-dl",
                resolutionHeight = 1080,
                videoCodec = "h264",
                releaseGroup = "group",
            ),
            SubtitleCandidate(
                flagEmoji = "DE",
                languageName = "German",
                languageCode = "de",
                displayLabel = "Show.S01E02.1080p.WEB-DL-GROUP.de.srt",
                releaseName = "Show.S01E02.1080p.WEB-DL-GROUP",
                provider = "OpenSubtitles.com",
                ratings = 8.8,
                voteCount = 120,
                downloadCount = 8_100,
                fromTrusted = true,
                matchTier = SubtitleMatchTier.EXACT_RELEASE,
                matchScore = 98,
                qualityScore = 84,
                rankingReasons = listOf("+68 Normalized exact release"),
                contentIdentityScore = 100,
                releaseMatchScore = 100,
                syncConfidenceScore = 98,
                matchQuality = SubtitleMatchQuality.BEST,
                matchExplanation = "Exact release match",
                evidence = listOf(
                    SubtitleEvidence(SubtitleEvidenceState.MATCH, "Exact S01E02"),
                    SubtitleEvidence(SubtitleEvidenceState.MATCH, "Exact release name"),
                ),
                sourceType = "web-dl",
                resolutionHeight = 1080,
                videoCodec = "h264",
                releaseGroup = "group",
            ),
        ),
        matchingRelease = "Show.S01E02.1080p.WEB-DL-GROUP",
        movieHashAvailable = false,
        hasStrongMatch = true,
        providerStatus = "OpenSubtitles.com: Loaded 1 of 25 · Subtitle addons: Not configured",
        openSubtitlesPageLimit = 2,
        canLoadMore = canLoadMore,
    )

    private fun pressRemoteKey(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        composeRule.waitForIdle()
    }

    private fun awaitFocused(text: String, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onAllNodesWithText(text, substring = substring)
                    .fetchSemanticsNodes()
                    .any { it.config.getOrNull(SemanticsProperties.Focused) == true }
            }.getOrDefault(false)
        }
    }
}
