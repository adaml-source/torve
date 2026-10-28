package com.torve.android.ui.replays

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.torve.android.remote.TorveRemoteDialog
import com.torve.android.device.DeviceFormFactor
import com.torve.android.R
import com.torve.data.replays.ReplayHighlight
import kotlinx.coroutines.delay

/** TV highlights use a fullscreen TV client; phones retain the in-app official IFrame player. */
@Composable internal fun ReplayHighlightPlayer(video: ReplayHighlight, onDismiss: () -> Unit, onOpenExternal: () -> Unit,
    onViewReady: (android.webkit.WebView) -> Unit = {},
    isTv: Boolean = DeviceFormFactor.isTv(LocalContext.current)) {
    val context = LocalContext.current
    if (isTv) {
        var missingPlayer by remember(video.id) { mutableStateOf(false) }
        val dismiss by rememberUpdatedState(onDismiss)
        LaunchedEffect(video.id) {
            if (launchReplayTvYouTube(context, video.id)) dismiss() else missingPlayer = true
        }
        if (missingPlayer) AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("highlights.player.missing"),
            title = { Text(stringResource(R.string.stream_player_not_found, "YouTube / SmartTube")) },
            text = { Text(stringResource(R.string.replay_highlight_tv_missing_player)) },
            confirmButton = { ReplayAction(localizedUiText("Close"), Modifier.testTag("highlights.player.close"), onClick = onDismiss) })
        return
    }
    val dismiss by rememberUpdatedState(onDismiss)
    var ready by remember(video.id) { mutableStateOf(false) }
    var playing by remember(video.id) { mutableStateOf(false) }
    var failure by remember(video.id) { mutableStateOf<Int?>(null) }
    var attempt by remember(video.id) { mutableIntStateOf(0) }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(video.id, attempt) { delay(15_000); if (!ready) failure = -1 }
    TorveRemoteDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ReplayAction(localizedUiText("Back to highlights"), Modifier.focusRequester(closeFocus).testTag("highlights.player.close"), onClick = onDismiss)
                Text(video.spoilerFreeTitle, Modifier.weight(1f), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Native controls sit outside the YouTube viewport; branding and player controls stay visible.
            Box(Modifier.fillMaxWidth().weight(1f).testTag(if (playing) "highlights.player.playing" else "highlights.player")) {
                if (failure != null) ReplayYouTubeFailure(failure!!,
                    onRetry = { ready = false; playing = false; failure = null; attempt++ },
                    onWatch = onOpenExternal, onChooseAnother = onDismiss)
                else key(attempt) {
                    ReplayYouTubeEmbed(video.id, Modifier.fillMaxSize(), onReady = { ready = true },
                        onState = { state ->
                            playing = state == 1
                            if (state == 0) dismiss()
                        }, onError = { code ->
                            android.util.Log.i("ReplayYouTube", "iframe_error=$code video=${video.id}")
                            playing = false; failure = code
                        }, onViewReady = onViewReady)
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; closeFocus.requestFocus() }
    }
}

@Composable internal fun ReplayYouTubeFailure(code: Int, onRetry: () -> Unit,
    onWatch: () -> Unit, onChooseAnother: () -> Unit) {
    Column(Modifier.widthIn(max = 620.dp).padding(24.dp).testTag("highlights.player.error.$code"),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(localizedUiText(when (code) {
            101, 150 -> "This channel requires YouTube playback"
            100 -> "This video is no longer available"
            153 -> "YouTube couldn’t verify this player"
            2 -> "This highlight link isn’t valid"
            else -> "YouTube playback couldn’t start"
        }), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(localizedUiText(if (code == 101 || code == 150)
            "Trailers can allow embedded playback while individual sports uploads restrict it. You can open this video’s YouTube page inside Torve."
            else "Try again, choose another highlight, or open the video’s YouTube page inside Torve."), color = Color.White.copy(alpha = .72f))
        ReplayAction(localizedUiText("Play via YouTube inside Torve"), Modifier.fillMaxWidth().testTag("highlights.player.watch"),
            detail = localizedUiText("YouTube’s page may show scores or results"), onClick = onWatch)
        if (code != 100 && code != 101 && code != 150) ReplayAction(localizedUiText("Try again"), Modifier.fillMaxWidth(), onClick = onRetry)
        ReplayAction(localizedUiText("Choose another highlight"), Modifier.fillMaxWidth(), onClick = onChooseAnother)
    }
}
