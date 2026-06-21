package com.torve.desktop.ui.v2.help

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.torve.desktop.ui.l10n.ds
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Cmd+/ overlay listing every keyboard shortcut available in the shell.
 *
 * Single source of truth for "what does this app respond to". Grouped by
 * surface so users can scan quickly. Esc or any click outside closes.
 */
@Composable
fun KeyboardHelpOverlay(onDismiss: () -> Unit) {
    val colors = TorveDesktopThemeTokens.colors
    val shortcutGroups = localizedShortcutGroups()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .width(680.dp)
                .heightIn(max = 580.dp)
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                .clickable(enabled = false, onClick = {}),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ds("Keyboard shortcuts"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TorveGhostButton(text = ds("Close"), onClick = onDismiss)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(shortcutGroups) { group ->
                        ShortcutGroup(group)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutGroup(group: ShortcutGroup) {
    val colors = TorveDesktopThemeTokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        group.shortcuts.forEach { shortcut ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShortcutKeys(shortcut.keys)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = shortcut.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ShortcutKeys(keys: List<String>) {
    val colors = TorveDesktopThemeTokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.forEachIndexed { index, key ->
            Surface(
                color = colors.fieldSurface,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.border(1.dp, colors.borderSubtle, RoundedCornerShape(4.dp)),
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (index < keys.size - 1) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

private data class Shortcut(val keys: List<String>, val description: String)
private data class ShortcutGroup(val name: String, val shortcuts: List<Shortcut>)

@Composable
private fun localizedShortcutGroups(): List<ShortcutGroup> = listOf(
    ShortcutGroup(
        name = ds("Navigation"),
        shortcuts = listOf(
            Shortcut(modAndKey("1"), ds("Home")),
            Shortcut(modAndKey("2"), ds("Movies")),
            Shortcut(modAndKey("3"), ds("Shows")),
            Shortcut(modAndKey("4"), ds("Search")),
            Shortcut(modAndKey("5"), ds("Library")),
            Shortcut(modAndKey("6"), ds("Live TV")),
            Shortcut(modAndKey(","), ds("Open Settings")),
            Shortcut(modAndKey("W"), ds("Close topmost overlay")),
        ),
    ),
    ShortcutGroup(
        name = ds("Power user"),
        shortcuts = listOf(
            Shortcut(modAndKey("K"), ds("Open command palette")),
            Shortcut(modAndKey("/"), ds("Show this keyboard help")),
            Shortcut(modAndKey("Q"), ds("Quit Torve")),
        ),
    ),
    ShortcutGroup(
        name = ds("Player (when video is playing)"),
        shortcuts = listOf(
            Shortcut(listOf("Space"), ds("Play / pause")),
            Shortcut(listOf("F"), ds("Toggle fullscreen")),
            Shortcut(listOf("F11"), ds("Toggle fullscreen")),
            Shortcut(listOf("Left"), ds("Seek back")),
            Shortcut(listOf("Right"), ds("Seek forward")),
            Shortcut(listOf("Up"), ds("Volume up")),
            Shortcut(listOf("Down"), ds("Volume down")),
            Shortcut(listOf("C"), ds("Change subtitles")),
            Shortcut(listOf("V"), ds("Change video mode")),
            Shortcut(listOf("PageUp", "PageDown"), ds("Previous / next IPTV channel")),
            Shortcut(listOf("M"), ds("Mute toggle")),
            Shortcut(listOf("S"), ds("Stop")),
            Shortcut(listOf("Esc"), ds("Exit fullscreen / close player")),
            Shortcut(listOf("H", "J"), ds("Subtitle delay - / +")),
            Shortcut(listOf("K", "L"), ds("Audio delay - / +")),
        ),
    ),
)

private fun modAndKey(key: String): List<String> {
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    val modLabel = if (isMac) "⌘" else "Ctrl"
    return listOf(modLabel, key)
}
