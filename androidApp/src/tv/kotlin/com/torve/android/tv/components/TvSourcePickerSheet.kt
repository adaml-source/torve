package com.torve.android.tv.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberLight
import com.torve.presentation.tvhome.TvSourcePickerOption
import com.torve.presentation.tvhome.TvSourcePickerState
import com.torve.presentation.tvhome.TvSourceTier
import kotlinx.coroutines.delay

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
    // The overlay owns Back while visible so one key press cannot fall through.
    BackHandler(onBack = onDismiss)
    val optionFocusRequesters = remember(state.options.size) {
        List(state.options.size) { FocusRequester() }
    }
    LaunchedEffect(state) {
        val firstFocusRequester = optionFocusRequesters.firstOrNull() ?: return@LaunchedEffect
        repeat(4) {
            delay(if (it == 0) 60L else 40L)
            runCatching { firstFocusRequester.requestFocus() }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            // Fire TV sends Back key-down before OnBackPressedDispatcher runs
            // on key-up. Consume key-down at the modal boundary so focus cannot
            // escape to the scrim and force a second press.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .focusProperties { canFocus = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .focusGroup()
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
                    focusRequester = optionFocusRequesters[index],
                    previousFocusRequester = optionFocusRequesters.getOrNull(index - 1),
                    nextFocusRequester = optionFocusRequesters.getOrNull(index + 1),
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
    focusRequester: FocusRequester,
    previousFocusRequester: FocusRequester?,
    nextFocusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = when (option.tier) {
        TvSourceTier.BEST -> Color(0xFF66BB6A)
        TvSourceTier.FALLBACK -> Color(0xFFFFB74D)
        TvSourceTier.RE_DOWNLOAD -> Color(0xFFE57373)
    }
    val container = if (focused) Amber.copy(alpha = 0.16f) else Color(0x1AFFFFFF)
    val borderColor = if (focused) AmberLight else Color(0x33FFFFFF)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .focusRequester(focusRequester)
            .focusProperties {
                up = previousFocusRequester ?: focusRequester
                down = nextFocusRequester ?: focusRequester
            }
            .onFocusChanged { focused = it.isFocused }
            // clickable owns the row's single focus target. A separate
            // focusable modifier in front of it consumes DPAD_CENTER before
            // clickable sees the key, allowing the scrim to dismiss instead
            // of selecting the source on Fire TV.
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
