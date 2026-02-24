package com.streamvault.android.ui.detail

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.streamvault.android.R
import com.streamvault.android.player.CastManager
import com.streamvault.android.player.ExternalPlayerLauncher
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.android.ui.theme.StreamVault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamActionSheet(
    url: String,
    title: String,
    posterUrl: String = "",
    onPlayInApp: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val installedPlayers = ExternalPlayerLauncher.getInstalledPlayers(context)

    // Google Cast
    val castManager = remember {
        try { CastManager(context) } catch (_: Exception) { null }
    }
    val castAvailable = remember {
        try {
            CastContext.getSharedInstance(context)
            true
        } catch (_: Exception) {
            false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Charcoal,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.action_stream_actions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Snow,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            // Play in-app
            ActionItem(
                icon = Icons.Default.PlayArrow,
                label = stringResource(R.string.action_play_in_torve),
                onClick = {
                    onPlayInApp()
                    onDismiss()
                },
            )

            // Cast to device
            if (castAvailable && castManager != null) {
                ActionItem(
                    icon = if (castManager.isCasting) Icons.Default.CastConnected else Icons.Default.Cast,
                    label = if (castManager.isCasting) "Cast (Connected)" else "Cast to Device",
                    onClick = {
                        if (castManager.isCasting) {
                            castManager.castMedia(
                                url = url,
                                title = title,
                                posterUrl = posterUrl.ifBlank { null },
                            )
                            onDismiss()
                        } else {
                            // Open the Cast dialog via MediaRouteButton programmatically
                            try {
                                val castContext = CastContext.getSharedInstance(context)
                                val routeSelector = castContext.mergedSelector
                                if (routeSelector != null) {
                                    val mediaRouteButton = MediaRouteButton(context)
                                    CastButtonFactory.setUpMediaRouteButton(context, mediaRouteButton)
                                    mediaRouteButton.performClick()
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, "Cast not available", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }

            HorizontalDivider(
                color = Steel.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 24.dp),
            )

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
                label = stringResource(R.string.action_play_external),
                onClick = {
                    ExternalPlayerLauncher.playWithChooser(context, url, title)
                    onDismiss()
                },
            )

            HorizontalDivider(
                color = Steel.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            // Download (queue internally)
            if (onDownload != null) {
                ActionItem(
                    icon = Icons.Default.Download,
                    label = stringResource(R.string.action_download),
                    onClick = {
                        onDownload()
                        Toast.makeText(context, context.getString(R.string.action_download_queued), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                )
            }

            // Download with VLC
            ActionItem(
                icon = Icons.Default.Download,
                label = "Download with VLC",
                accentColor = true,
                onClick = {
                    val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
                        setPackage("org.videolan.vlc")
                        setDataAndType(Uri.parse(url), "video/*")
                        putExtra("title", title)
                        putExtra(Intent.EXTRA_TITLE, title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(vlcIntent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "VLC is not installed", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
            )

            HorizontalDivider(
                color = Steel.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            // Copy URL
            ActionItem(
                icon = Icons.Default.ContentCopy,
                label = stringResource(R.string.action_copy_url),
                onClick = {
                    ExternalPlayerLauncher.copyUrl(context, url)
                    Toast.makeText(context, context.getString(R.string.action_url_copied), Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            )

            // Share
            ActionItem(
                icon = Icons.Default.Share,
                label = stringResource(R.string.common_share),
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
    accentColor: Boolean = false,
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
            tint = if (accentColor) Amber else Snow,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (accentColor) Amber else Snow,
        )
    }
}
