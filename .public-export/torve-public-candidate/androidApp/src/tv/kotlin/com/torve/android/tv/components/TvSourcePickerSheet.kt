package com.torve.android.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.presentation.tvhome.TvSourcePickerOption
import com.torve.presentation.tvhome.TvSourcePickerState
import com.torve.presentation.tvhome.TvSourceTier

/**
 * D-pad-driven source picker sheet for TV detail (Prompt 11B).
 *
 * Renders [state.options] as a vertical list. The first option (BEST)
 * gets initial focus; UP/DOWN walks the list; OK invokes [onSelect]
 * with the chosen option's route. The caller decides whether to launch
 * the player directly or to fall through to the legacy stream picker
 * (only meaningful for [PlaybackRoute.ProviderStream]).
 *
 * The sheet is dismissable: Back closes it without selecting. Caller
 * passes [onDismiss] to clean up its open state.
 *
 * Hidden when [state] is null so callers can render this conditionally.
 */
@Composable
internal fun TvSourcePickerSheet(
    state: TvSourcePickerState?,
    onSelect: (TvSourcePickerOption) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null) return
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state) {
        runCatching { firstFocusRequester.requestFocus() }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 380.dp, max = 560.dp)
                .background(Color(0xFF111820), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF2A3340), RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Choose source",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            state.providerIssue?.let { issue ->
                Text(
                    text = issue,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB74D),
                )
            }
            state.options.forEachIndexed { index, opt ->
                SourceRow(
                    option = opt,
                    isFirst = index == 0,
                    focusRequester = if (index == 0) firstFocusRequester else null,
                    onClick = { onSelect(opt) },
                )
            }
            if (!state.canAutoPlay) {
                Text(
                    text = "No source is ready right now. Add a download and try again.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    option: TvSourcePickerOption,
    isFirst: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(isFirst) }
    val accent = when (option.tier) {
        TvSourceTier.BEST -> Color(0xFF66BB6A)
        TvSourceTier.FALLBACK -> Color(0xFFFFB74D)
        TvSourceTier.RE_DOWNLOAD -> Color(0xFFE57373)
    }
    val container = if (focused) Color(0x335FB1FF) else Color(0x1AFFFFFF)
    val borderColor = if (focused) Color(0xFF5FB1FF) else Color(0x33FFFFFF)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${option.label} • ${option.tier.displayLabel()}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Text(
            text = option.hint,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

private fun TvSourceTier.displayLabel(): String = when (this) {
    TvSourceTier.BEST -> "Best"
    TvSourceTier.FALLBACK -> "Fallback"
    TvSourceTier.RE_DOWNLOAD -> "Download to play"
}
