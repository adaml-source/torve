package com.torve.android.ui.player

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow

/** Leaves faces and subtitles visible while previewing a timing adjustment. */
@Composable
internal fun TvDelayAdjustmentStrip(
    title: String,
    currentDelayMs: Int,
    range: IntRange,
    onPreviewDelay: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var delayMs by remember(currentDelayMs) { mutableIntStateOf(currentDelayMs.coerceIn(range)) }
    val valueRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val resetRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }
    var valueFocused by remember { mutableStateOf(false) }
    var stripHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(8) {
            withFrameNanos { }
            if (stripHasFocus) return@LaunchedEffect
            runCatching { valueRequester.requestFocus() }
            withFrameNanos { }
            if (stripHasFocus) return@LaunchedEffect
        }
    }
    val value = when {
        delayMs > 0 -> "+$delayMs ms"
        delayMs < 0 -> "−${-delayMs} ms"
        else -> "0 ms"
    }
    fun adjust(direction: Int) {
        val next = (delayMs + direction * 50).coerceIn(range)
        if (next != delayMs) {
            delayMs = next
            onPreviewDelay(next)
        }
    }
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.testTag("tv_delay_strip")
                .onFocusChanged { stripHasFocus = it.hasFocus }.focusGroup()
                .background(Color.Black.copy(alpha = .32f), shape)
                .border(1.dp, Color.White.copy(alpha = .16f), shape)
                .padding(horizontal = 14.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localizedUiText(title), color = Snow, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                "‹  $value  ›", color = AmberLight, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("tv_delay_value").width(150.dp).height(40.dp)
                    .focusRequester(valueRequester)
                    .focusProperties { down = saveRequester; up = saveRequester }
                    .onFocusChanged { valueFocused = it.isFocused }
                    .border(1.dp, if (valueFocused) AmberLight else Color.Transparent, RoundedCornerShape(18.dp))
                    .semantics { stateDescription = "$title: $value. Left or right changes 50 milliseconds. OK saves. Down opens actions." }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && event.key in listOf(Key.DirectionLeft, Key.DirectionRight,
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) return@onPreviewKeyEvent true
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { adjust(-1); true }
                            Key.DirectionRight -> { adjust(1); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onSave(delayMs); true }
                            else -> false
                        }
                    }
                    .clickable { onSave(delayMs) }
                    .wrapContentSize(Alignment.Center),
            )
            Text("50 ms", color = Silver, style = MaterialTheme.typography.labelMedium)
            DelayStripAction(stringResource(R.string.common_save), saveRequester,
                Modifier.focusProperties { left = valueRequester; right = resetRequester; up = valueRequester; down = valueRequester }) { onSave(delayMs) }
            DelayStripAction(stringResource(R.string.common_reset), resetRequester,
                Modifier.focusProperties { left = saveRequester; right = closeRequester; up = valueRequester; down = valueRequester }) {
                delayMs = 0
                onReset()
            }
            DelayStripAction(stringResource(R.string.common_close), closeRequester,
                Modifier.focusProperties { left = resetRequester; right = valueRequester; up = valueRequester; down = valueRequester }, onDismiss)
        }
    }
}

@Composable
private fun DelayStripAction(label: String, requester: FocusRequester, modifier: Modifier, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.height(40.dp).focusRequester(requester)
        .onFocusChanged { focused = it.isFocused }
        .border(1.dp, if (focused) AmberLight else Color.Transparent, shape)
        .background(if (focused) AmberLight.copy(alpha = .12f) else Color.Transparent, shape)
        .clickable(onClick = action).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(localizedUiText(label), color = if (focused) AmberLight else Snow, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}
