package com.torve.android.ui.player

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.test.TorveTestHostActivity
import com.torve.domain.player.PlaybackSegment
import com.torve.domain.player.SegmentConfidence
import com.torve.domain.player.SegmentEvidenceSource
import com.torve.domain.player.SegmentType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TvPlaybackSegmentActionNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun appearingSkipIntroTakesPlaybackFocusAcceptsRemoteOkAndRestoresSurface() {
        val activeSegment = mutableStateOf<PlaybackSegment?>(null)
        val coordinator = PlaybackFocusCoordinator()
        var skipped = false

        composeRule.setContent {
            val surfaceRequester = remember { FocusRequester() }
            RegisterFocusRegion(coordinator, PlaybackFocusRegion.PlayerSurface) {
                surfaceRequester.requestFocus()
                true
            }
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(surfaceRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                coordinator.reportFocusedRegion(PlaybackFocusRegion.PlayerSurface)
                            }
                        }
                        .focusable()
                        .testTag(PLAYER_SURFACE_TEST_TAG)
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown &&
                                !segmentActionOwnsActivationKey(
                                    isTv = true,
                                    currentRegion = coordinator.currentRegion,
                                    key = event.key,
                                )
                        },
                ) {
                    PlaybackSegmentOverlay(
                        activeSegment = activeSegment.value,
                        segments = emptyList(),
                        positionMs = 0L,
                        focusCoordinator = coordinator,
                        autoFocus = true,
                        onSkip = {
                            skipped = true
                            activeSegment.value = null
                        },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(coordinator.requestFocusToRegion(PlaybackFocusRegion.PlayerSurface))
        }
        awaitFocused(PLAYER_SURFACE_TEST_TAG)
        composeRule.runOnIdle { activeSegment.value = intro() }
        awaitFocused(PLAYBACK_SEGMENT_ACTION_TEST_TAG)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.waitUntil(timeoutMillis = 5_000) { skipped }
        awaitFocused(PLAYER_SURFACE_TEST_TAG)
        assertTrue(skipped)
    }

    private fun awaitFocused(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsFocused()
                true
            }.getOrDefault(false)
        }
    }

    private fun intro() = PlaybackSegment(
        id = "intro-1",
        type = SegmentType.INTRO,
        startMs = 10_000L,
        endMs = 30_000L,
        confidence = 0.75,
        confidenceLevel = SegmentConfidence.MEDIUM,
        source = SegmentEvidenceSource.COMMUNITY_DATABASE,
        evidence = emptyList(),
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        version = 1,
    )

    private companion object {
        const val PLAYER_SURFACE_TEST_TAG = "player_surface"
    }
}
