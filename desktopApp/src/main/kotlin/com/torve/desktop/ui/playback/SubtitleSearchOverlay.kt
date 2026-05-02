package com.torve.desktop.ui.playback

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.data.addon.StremioSubtitle
import com.torve.desktop.playback.DesktopPlayerController
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

/**
 * Modal sheet over the player surface that searches every installed Stremio
 * subtitle addon for the active media and lets the user attach one with a
 * click. Fires once when [onDismiss] is invoked or after a successful pick.
 */
@Composable
fun SubtitleSearchOverlay(
    playerController: DesktopPlayerController,
    onPickSubtitle: (StremioSubtitle) -> Unit,
    onDismiss: () -> Unit,
    onAttached: (StremioSubtitle) -> Unit = {},
) {
    val colors = TorveDesktopThemeTokens.colors
    var loading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<StremioSubtitle>>(emptyList()) }
    var languageFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        results = playerController.searchOnlineSubtitles()
        loading = false
    }

    val visibleResults = remember(results, languageFilter) {
        languageFilter?.let { lang ->
            results.filter { it.lang.equals(lang, ignoreCase = true) }
        } ?: results
    }
    val languages = remember(results) {
        results.map { it.lang.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 540.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                .clickable(enabled = false, onClick = {}),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Search subtitles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Text(
                    "Sources are pulled from every Stremio subtitle addon you have installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )

                if (languages.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TorveGhostButton(
                            text = if (languageFilter == null) "All langs" else "All",
                            onClick = { languageFilter = null },
                        )
                        languages.take(8).forEach { lang ->
                            TorveGhostButton(
                                text = lang.uppercase(),
                                onClick = {
                                    languageFilter = if (languageFilter == lang) null else lang
                                },
                            )
                        }
                    }
                }

                HorizontalDivider()

                when {
                    loading && results.isEmpty() -> {
                        Text(
                            "Querying subtitle addons...",
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    !loading && results.isEmpty() -> {
                        Text(
                            "No subtitles found. Make sure you have a subtitle addon (e.g. OpenSubtitles) installed.",
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(visibleResults) { sub ->
                                SubtitleRow(
                                    subtitle = sub,
                                    onClick = {
                                        runCatching { onPickSubtitle(sub) }
                                        onAttached(sub)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f, fill = false))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    TorveGhostButton(text = "Close", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SubtitleRow(
    subtitle: StremioSubtitle,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        color = colors.cardSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorveBadge(
                text = subtitle.lang.uppercase().ifBlank { "??" },
                tone = TorveBadgeTone.Accent,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subtitle.label?.takeIf { it.isNotBlank() }
                        ?: subtitle.id?.takeIf { it.isNotBlank() }
                        ?: subtitle.url.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
