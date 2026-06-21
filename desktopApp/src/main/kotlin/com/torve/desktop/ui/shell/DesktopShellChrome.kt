package com.torve.desktop.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.desktop.DesktopReleaseInfo
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveDropdownScaffold
import com.torve.desktop.ui.navigation.DesktopDestination
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

private fun DesktopDestination.icon(): ImageVector = when (this) {
    DesktopDestination.HOME -> Icons.Filled.Home
    DesktopDestination.MOVIES -> Icons.Filled.Movie
    DesktopDestination.TV_SHOWS -> Icons.Filled.Tv
    DesktopDestination.SEARCH -> Icons.Filled.Search
    DesktopDestination.LIBRARY -> Icons.Filled.VideoLibrary
    DesktopDestination.LIVE_TV -> Icons.Filled.LiveTv
    DesktopDestination.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun DesktopSidebar(
    authState: DesktopAuthUiState,
    currentDestination: DesktopDestination,
    releaseInfo: DesktopReleaseInfo,
    onSelectDestination: (DesktopDestination) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val user = authState.user ?: return
    val appIcon = remember {
        runCatching {
            val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("torve_icon.png")?.readBytes()
            bytes?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
        }.getOrNull()
    }

    Surface(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight(),
        color = colors.sidebarSurface.copy(alpha = 0.22f),
        shape = RoundedCornerShape(radii.sm),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.03f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Real Torve app icon
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = "Torve",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.size(36.dp),
                    color = colors.accent,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "T",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = colors.shellBackground,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Icon-only navigation - no labels
            DesktopDestination.entries.filter { it.showInNav }.forEach { destination ->
                DesktopNavIcon(
                    icon = destination.icon(),
                    contentDescription = destination.label,
                    selected = destination == currentDestination,
                    onClick = { onSelectDestination(destination) },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Settings utility icon at bottom - icon only
            DesktopNavIcon(
                icon = Icons.Filled.Settings,
                contentDescription = "Settings",
                selected = currentDestination == DesktopDestination.SETTINGS,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun DesktopNavIcon(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected) colors.accentContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = if (selected) colors.textPrimary else colors.textMuted,
            )
        }
    }
}

@Composable
fun DesktopFloatingUserBadge(
    accessStateLabel: String,
    userLabel: String,
    accessMenuExpanded: Boolean,
    onToggleAccessMenu: () -> Unit,
    onDismissAccessMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    Box {
        Surface(
            modifier = Modifier.clickable(onClick = onToggleAccessMenu),
            color = colors.shellBackground.copy(alpha = 0.52f),
            shape = RoundedCornerShape(radii.md),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.08f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TorveBadge(accessStateLabel, tone = TorveBadgeTone.Accent)
                Text(
                    text = userLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        TorveDropdownScaffold(
            expanded = accessMenuExpanded,
            onDismissRequest = onDismissAccessMenu,
            items = listOf(
                "Open Settings" to {
                    onDismissAccessMenu()
                    onOpenSettings()
                },
                "Sign Out" to {
                    onDismissAccessMenu()
                    onSignOut()
                },
            ),
        )
    }
}

@Composable
fun DesktopShellHeader(
    destination: DesktopDestination,
    releaseInfo: DesktopReleaseInfo,
    searchQuery: String,
    accessStateLabel: String,
    onOpenSearch: () -> Unit,
    accessMenuExpanded: Boolean,
    onDismissAccessMenu: () -> Unit,
    onToggleAccessMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    userLabel: String,
    contentFirst: Boolean,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.headerSurface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(radii.lg),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(contentAlignment = Alignment.CenterEnd) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TorveBadge(accessStateLabel, tone = TorveBadgeTone.Accent)
                    Surface(
                        modifier = Modifier.clickable(onClick = onToggleAccessMenu),
                        color = colors.fieldSurface.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(radii.md),
                    ) {
                        Text(
                            text = userLabel,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
                TorveDropdownScaffold(
                    expanded = accessMenuExpanded,
                    onDismissRequest = onDismissAccessMenu,
                    items = listOf(
                        "Open Settings" to {
                            onDismissAccessMenu()
                            onOpenSettings()
                        },
                        "Sign Out" to {
                            onDismissAccessMenu()
                            onSignOut()
                        },
                    ),
                )
            }
        }
    }
}
