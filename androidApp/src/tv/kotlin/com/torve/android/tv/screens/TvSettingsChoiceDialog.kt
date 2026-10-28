package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsFocusFrame
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.torve.android.remote.TorveRemoteDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first
import com.torve.android.tv.components.tvSettingsLabel as parityLabel

internal data class TvSettingsChoice(
    val title: String, val selected: String, val options: List<Pair<String, String>>,
    val select: (String) -> Unit,
)

/** Opening a picker never changes the preference; only confirming a value does. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvSettingsChoiceDialog(choice: TvSettingsChoice, dismiss: () -> Unit) {
    val selectedIndex = choice.options.indexOfFirst { it.first == choice.selected }.coerceAtLeast(0)
    val requesters = remember(choice) { choice.options.map { FocusRequester() } }
    val cancel = remember { FocusRequester() }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = LocalWindowInfo.current
        Surface(shape = RoundedCornerShape(20.dp), color = com.torve.android.tv.components.TvSettingsDialogSurface, contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)).testTag("settings.choice")) {
            Column(Modifier.width(460.dp).heightIn(max = 470.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(parityLabel(choice.title), style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.heightIn(max = 300.dp), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(choice.options, key = { _, item -> item.first }) { index, item ->
                        var focused by remember { mutableStateOf(false) }
                        Surface(onClick = { choice.select(item.first); dismiss() }, shape = RoundedCornerShape(20.dp),
                            color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .15f) else Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().focusRequester(requesters[index])
                                .focusProperties { if (index == 0) up = FocusRequester.Cancel; if (index == requesters.lastIndex) down = cancel }
                                .onFocusChanged { focused = it.isFocused }
                                .tvSettingsFocusFrame(focused)) {
                            Text((if (item.first == choice.selected) "✓  " else "") + parityLabel(item.second), modifier = Modifier.padding(14.dp))
                        }
                    }
                }
                TextButton(onClick = dismiss, modifier = Modifier.focusRequester(cancel)) { Text(parityLabel("Cancel")) }
            }
        }
        LaunchedEffect(choice) {
            snapshotFlow { window.isWindowFocused }.first { it }
            withFrameNanos { }
            requesters.getOrNull(selectedIndex)?.requestFocus()
        }
    }
}
