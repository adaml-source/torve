package com.torve.android.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.torve.android.player.LivePlayerEngineId
import com.torve.android.player.PlaybackRuntimeInfo
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.screens.LiveBufferPreset
import com.torve.android.tv.screens.LivePictureFormatOption
import com.torve.android.tv.screens.LivePlaybackMenuOverlay
import com.torve.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TvLivePlaybackMenuUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test fun oneStopControlReceivesRemoteFocusAndStopsOnce() {
        var stops = 0
        var pauses = 0
        composeRule.setContent { menu(onStop = { stops++ }, onPause = { pauses++ }) }
        settle()
        composeRule.onAllNodesWithText("Stop").assertCountEquals(1)
        key("Pause", Key.DirectionRight)
        composeRule.onNodeWithText("Stop").assertIsFocused()
        key("Stop", Key.DirectionCenter)
        composeRule.runOnIdle { assertEquals(1, stops); assertEquals(0, pauses) }
    }

    @Test fun backFromAudioPanelReturnsToAudioWithoutDismissingPlaybackControls() {
        var dismissals = 0
        var stops = 0
        composeRule.setContent { menu(onDismiss = { dismissals++ }, onStop = { stops++ }) }
        settle()
        for (control in listOf("Pause", "Stop", "Channels", "Guide")) key(control, Key.DirectionRight)
        key("Audio", Key.DirectionCenter)
        composeRule.onNodeWithText("Audio track").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        settle()
        composeRule.onNodeWithText("Audio").assertIsFocused()
        composeRule.onNodeWithText("Audio track").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, dismissals); assertEquals(0, stops) }
    }

    @Test fun subtitleSyncUsesTheThinStripAndBackRestoresTheSyncControl() {
        var delayMs by mutableIntStateOf(0)
        var dismissals = 0
        composeRule.setContent { menu(onStop = {}, onDismiss = { dismissals++ }, subtitleDelayMs = delayMs, onDelay = { delayMs = it }) }
        settle()
        for (control in listOf("Pause", "Stop", "Channels", "Guide", "Audio", "Subtitles")) key(control, Key.DirectionRight)
        key("Sync", Key.DirectionCenter)
        composeRule.onNodeWithTag("tv_delay_value").assertIsFocused()
        composeRule.onNodeWithText("Pause").assertDoesNotExist()
        composeRule.onNodeWithTag("tv_delay_value").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertEquals(50, delayMs) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        settle()
        composeRule.onNodeWithText("Sync").assertIsFocused()
        composeRule.runOnIdle { assertEquals(0, dismissals) }
    }

    @Composable private fun menu(onStop: () -> Unit, onPause: () -> Unit = {}, onDismiss: () -> Unit = {},
        subtitleDelayMs: Int = 0, onDelay: (Int) -> Unit = {}) {
        CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
            MaterialTheme {
                LivePlaybackMenuOverlay(
                    currentChannel = Channel("News One", "https://example.test/live"), channelNumber = 1,
                    currentProgramme = null, videoResolution = "1920×1080", audioCodec = "AAC",
                    isFavorite = false, pictureFormats = listOf(LivePictureFormatOption("source", "Source")),
                    selectedPictureFormatKey = "source", audioTracks = emptyList(), subtitleTracks = emptyList(),
                    playbackRuntimeInfo = PlaybackRuntimeInfo(LivePlayerEngineId.EXOPLAYER),
                    sleepTimerMinutes = null, sleepTimerRemainingLabel = null, pipSupported = false,
                    multiviewAvailable = false, timeshiftAvailable = false, replayTimeshiftActive = false,
                    timeshiftPaused = false, liveOffsetMs = 0, timeshiftPositionMs = 0, timeshiftDurationMs = 0,
                    selectedBufferPreset = LiveBufferPreset.MEDIUM, canStartFromBeginning = false,
                    isPlaying = true, subtitleDelayMs = subtitleDelayMs, externalInteractionId = 0,
                    onDismiss = onDismiss, onOpenChannelList = {}, onOpenGuide = {}, onOpenChannelInfo = {},
                    onToggleFavorite = {}, onReloadStream = {}, onEnterPip = {}, onToggleMultiview = {},
                    onToggleTimeshiftPause = {}, onGoLive = {}, onSeekTimeshift = {}, onStartFromBeginning = {},
                    onTogglePlayPause = onPause, onStopPlayback = onStop, onSetSubtitleDelay = onDelay,
                    onSelectPictureFormat = {}, onSelectAudioTrack = {}, onSelectSubtitleTrack = {},
                    onDisableSubtitles = {}, onSelectAudioOutputMode = {}, onSelectBufferSize = {}, onSelectSleepTimer = {},
                )
            }
        }
    }

    private fun key(label: String, key: Key) {
        composeRule.onNode(hasText(label) and isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(250)
        Thread.sleep(180)
        composeRule.waitForIdle()
    }
}
