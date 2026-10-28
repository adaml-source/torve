package com.torve.android.ui.player

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Preview against the playing video; Save commits, Close restores the saved offset. */
@Composable
internal fun MobileDelayBar(title: String, currentDelayMs: Int, onPreview: (Int) -> Unit,
    onSave: (Int) -> Unit, onClose: () -> Unit) {
    val original = remember(title) { currentDelayMs }
    var delay by remember(title) { mutableIntStateOf((currentDelayMs / 50) * 50) }
    var committed by remember(title) { mutableStateOf(false) }
    val preview by rememberUpdatedState(onPreview)
    DisposableEffect(title) { onDispose { if (!committed) preview(original) } }
    fun adjust(value: Int) { delay = value.coerceIn(-2000, 2000); preview(delay) }
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        Surface(Modifier.align(Alignment.BottomCenter).widthIn(max = 800.dp).fillMaxWidth().padding(8.dp).testTag("mobile_delay_bar"),
            shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .38f), contentColor = Color.White) {
            BoxWithConstraints(Modifier.padding(horizontal = 8.dp)) {
                val compact = maxWidth < 560.dp
                Column {
                    if (compact) Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedUiText(title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text("${if (delay > 0) "+" else ""}$delay ms", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!compact) Text(localizedUiText(title), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { adjust(delay - 50) }, enabled = delay > -2000, modifier = Modifier.size(48.dp).testTag("delay_minus")) {
                            Icon(Icons.Rounded.Remove, contentDescription = localizedUiText("Decrease $title by 50 milliseconds"))
                        }
                        if (!compact) Text("${if (delay > 0) "+" else ""}$delay ms", Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { adjust(delay + 50) }, enabled = delay < 2000, modifier = Modifier.size(48.dp).testTag("delay_plus")) {
                            Icon(Icons.Rounded.Add, contentDescription = localizedUiText("Increase $title by 50 milliseconds"))
                        }
                        if (compact) Spacer(Modifier.weight(1f))
                        TextButton(onClick = { adjust(0) }) { Text(localizedUiText("Reset"), color = Color.White) }
                        TextButton(onClick = { committed = true; onSave(delay) }, modifier = Modifier.testTag("delay_save")) { Text(localizedUiText("Save")) }
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("delay_close")) { Icon(Icons.Rounded.Close, contentDescription = localizedUiText("Close $title")) }
                    }
                }
            }
        }
    }
}
