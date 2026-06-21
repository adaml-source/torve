package com.torve.desktop.ui.v2.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import com.torve.desktop.ui.l10n.ds
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Single command surfaced in the palette. [hint] shows a short helper
 * (destination/category) under the label; [keywords] lets matching find
 * the command via typed terms that aren't in the visible label
 * (e.g. "preferences" → Settings).
 */
data class CommandPaletteAction(
    val id: String,
    val label: String,
    val hint: String? = null,
    val icon: ImageVector? = null,
    val keywords: List<String> = emptyList(),
    val action: () -> Unit,
)

/**
 * Cmd+K / Ctrl+K command palette. Floating modal centered over the shell
 * with a search field and fuzzy-ish action list. Esc closes; Enter
 * activates the highlighted item; ↑/↓ moves the highlight.
 *
 * Filtering: case-insensitive substring against label, hint, and the
 * action's [CommandPaletteAction.keywords]. Cheap and predictable -
 * good enough for a small action set.
 */
@Composable
fun CommandPalette(
    actions: List<CommandPaletteAction>,
    onDismiss: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    var query by remember { mutableStateOf("") }
    var highlight by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query, actions) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) actions
        else actions.filter { action ->
            action.label.lowercase().contains(q) ||
                action.hint?.lowercase()?.contains(q) == true ||
                action.keywords.any { it.lowercase().contains(q) }
        }
    }

    LaunchedEffect(filtered.size) {
        if (highlight !in filtered.indices) highlight = 0
    }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onDismiss(); true }
                    Key.DirectionDown -> {
                        if (filtered.isNotEmpty()) {
                            highlight = (highlight + 1) % filtered.size
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (filtered.isNotEmpty()) {
                            highlight = (highlight - 1 + filtered.size) % filtered.size
                        }
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        filtered.getOrNull(highlight)?.let { action ->
                            action.action()
                            onDismiss()
                        }
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .padding(top = 80.dp)
                .width(640.dp)
                .heightIn(max = 520.dp)
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                // Eat clicks on the panel so the outer scrim doesn't
                // dismiss while the user reaches for an action.
                .clickable(enabled = false, onClick = {}),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; highlight = 0 },
                        placeholder = { Text(ds("Type a command or page..."), color = colors.textSecondary) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.borderSubtle,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                }

                if (filtered.isEmpty()) {
                    Text(
                        "No matches for \"$query\".",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(filtered, key = { _, action -> action.id }) { index, action ->
                            CommandRow(
                                action = action,
                                highlighted = index == highlight,
                                onClick = {
                                    action.action()
                                    onDismiss()
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.size(0.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HintChip("↑ ↓", "navigate")
                    HintChip("Enter", "run")
                    HintChip("Esc", "close")
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    action: CommandPaletteAction,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (highlighted) colors.accentContainer else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            action.icon?.let { Icon(it, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(18.dp)) }
            Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                action.hint?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HintChip(key: String, label: String) {
    val colors = TorveDesktopThemeTokens.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = colors.fieldSurface,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
    }
}
