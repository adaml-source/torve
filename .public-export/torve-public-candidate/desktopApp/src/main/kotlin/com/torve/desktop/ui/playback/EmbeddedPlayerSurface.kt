package com.torve.desktop.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.torve.desktop.player.VlcDesktopPlaybackEngine
import com.torve.desktop.player.VlcPlayerChromeHost
import com.torve.desktop.player.VlcSurfaceCallbacks
import com.torve.desktop.player.VlcVideoSurfaceHost
import javax.swing.SwingUtilities

/**
 * Compose wrapper for the embedded VLC player surface.
 *
 * The video and controls are both rendered inside a single JPanel using
 * JLayeredPane. No JWindow overlays. No separate top-level windows.
 * The chrome controls are lightweight Swing components layered above
 * the video via CallbackMediaPlayerComponent (Java2D rendering).
 */
@Composable
fun EmbeddedVlcPlayerSurface(
    engine: VlcDesktopPlaybackEngine,
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onCloseState = rememberUpdatedState(onClose)
    val onStopState = rememberUpdatedState(onStop)

    // Create fresh instances for each composition entry so stopping and
    // replaying works correctly (no stale disposed=true flags).
    val chromeHost = remember(engine) {
        VlcPlayerChromeHost(
            engine = engine,
            onClose = { onCloseState.value() },
            onStop = { onStopState.value() },
        )
    }

    val callbacks = remember(engine) {
        VlcSurfaceCallbacks().apply {
            this.onPlayPauseToggle = {
                SwingUtilities.invokeLater {
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            if (engine.isPlaying()) engine.pause() else engine.play()
                        }
                    }
                }
            }
            this.onStop = { onStopState.value() }
            this.onSeekBack10 = { engine.seekRelative(-10_000) }
            this.onSeekForward30 = { engine.seekRelative(30_000) }
            this.onVolumeUp = { engine.volume.increaseVolume() }
            this.onVolumeDown = { engine.volume.decreaseVolume() }
            this.onMuteToggle = { engine.volume.toggleMute() }
            this.onEscPressed = {
                if (engine.fullscreen.isFullscreen) {
                    engine.fullscreen.exitFullscreen()
                } else {
                    onCloseState.value()
                }
            }
            this.onMouseMoved = { chromeHost.showTemporarily() }
            this.onSubtitleDelayIncrease = { engine.subtitle.increaseDelay() }
            this.onSubtitleDelayDecrease = { engine.subtitle.decreaseDelay() }
            this.onAudioDelayIncrease = { engine.audio.increaseDelay() }
            this.onAudioDelayDecrease = { engine.audio.decreaseDelay() }
        }
    }

    val surfaceHost = remember(engine, chromeHost) {
        VlcVideoSurfaceHost(engine, callbacks, chromeHost)
    }

    SwingPanel(factory = { surfaceHost }, modifier = modifier)

    SideEffect {
        SwingUtilities.invokeLater {
            chromeHost.updateActions(
                onClose = { onCloseState.value() },
                onStop = { onStopState.value() },
            )
            chromeHost.setTitle(title, subtitle)
            surfaceHost.showChrome("compose-mounted")
        }
    }

    DisposableEffect(engine, surfaceHost, chromeHost) {
        onDispose {
            SwingUtilities.invokeLater {
                surfaceHost.dispose()
            }
        }
    }
}
