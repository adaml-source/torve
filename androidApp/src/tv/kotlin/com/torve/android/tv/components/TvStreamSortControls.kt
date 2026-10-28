package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.data.addon.StreamSortMode

@Composable
fun TvStreamSortControls(
    sortMode: StreamSortMode,
    onSortModeChanged: (StreamSortMode) -> Unit,
    onFocused: (FocusRequester) -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(localizedUiText("Sort:"), color = Silver, style = MaterialTheme.typography.labelMedium)
        StreamSortMode.entries.forEach { mode ->
            val requester = remember(mode) { FocusRequester() }
            var focused by remember(mode) { mutableStateOf(false) }
            val selected = sortMode == mode
            val label = when (mode) {
                StreamSortMode.DEFAULT -> "Recommended"
                StreamSortMode.SIZE_DESC -> "Largest first"
                StreamSortMode.SIZE_ASC -> "Smallest first"
            }
            Box(
                modifier = Modifier
                    .background(if (selected) Amber else Gunmetal, RoundedCornerShape(20.dp))
                    .border(2.dp, if (focused) AmberLight else Steel, RoundedCornerShape(20.dp))
                    .focusRequester(requester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) onFocused(requester)
                    }
                    .semantics { this.selected = selected }
                    .clickable { onSortModeChanged(mode) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(localizedUiText(label), color = if (selected) Obsidian else Snow, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
