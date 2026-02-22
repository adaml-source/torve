package com.streamvault.android.ui.detail

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.player.ExternalPlayerLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamActionSheet(
    url: String,
    title: String,
    onPlayInApp: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val installedPlayers = ExternalPlayerLauncher.getInstalledPlayers(context)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Stream Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            // Play in-app
            ActionItem(
                icon = Icons.Default.PlayArrow,
                label = "Play in StreamVault",
                onClick = {
                    onPlayInApp()
                    onDismiss()
                },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

            // Installed external players
            installedPlayers.forEach { player ->
                ActionItem(
                    icon = Icons.Default.VideoLibrary,
                    label = "Play in ${player.label}",
                    onClick = {
                        val launched = ExternalPlayerLauncher.playInExternalPlayer(
                            context = context,
                            url = url,
                            title = title,
                            player = player,
                        )
                        if (!launched) {
                            Toast.makeText(context, "${player.label} not found", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    },
                )
            }

            // External player chooser (always available)
            ActionItem(
                icon = Icons.Default.OpenInNew,
                label = "Play in External Player...",
                onClick = {
                    ExternalPlayerLauncher.playWithChooser(context, url, title)
                    onDismiss()
                },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))

            // Download
            if (onDownload != null) {
                ActionItem(
                    icon = Icons.Default.Download,
                    label = "Download",
                    onClick = {
                        onDownload()
                        onDismiss()
                    },
                )
            }

            // Copy URL
            ActionItem(
                icon = Icons.Default.ContentCopy,
                label = "Copy URL",
                onClick = {
                    ExternalPlayerLauncher.copyUrl(context, url)
                    Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            )

            // Share
            ActionItem(
                icon = Icons.Default.Share,
                label = "Share",
                onClick = {
                    ExternalPlayerLauncher.shareUrl(context, url, title)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
