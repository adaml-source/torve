package com.streamvault.android.tv.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.sync.SyncCoordinator
import org.koin.compose.koinInject

private data class TvSettingRow(val title: String, val subtitle: String, val onClick: () -> Unit = {})

@Composable
fun TvSettingsScreen(
    railFocusRequester: FocusRequester,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    syncCoordinator: SyncCoordinator = koinInject(),
) {
    val syncState by syncCoordinator.state.collectAsState()
    LaunchedEffect(syncState.isAuthenticated) {
        if (!syncState.isAuthenticated && syncState.pairingCode == null && !syncState.isLoading) {
            syncCoordinator.startTvPairingFlow()
        }
    }

    val rows = remember(syncState.isAuthenticated) {
        val accountSubtitle = if (syncState.isAuthenticated) {
            "Connected as ${syncState.userEmail ?: "paired account"}"
        } else {
            "Waiting for mobile pairing claim"
        }
        listOf(
            TvSettingRow(
                title = "Account",
                subtitle = accountSubtitle,
                onClick = {
                    if (!syncState.isAuthenticated) {
                        syncCoordinator.startTvPairingFlow()
                    }
                },
            ),
            TvSettingRow(
                title = "Realtime",
                subtitle = "WebSocket status: ${syncState.wsStatus}",
            ),
            TvSettingRow(
                title = "Integrations",
                subtitle = "Google Cast is optional and does not crash on Fire TV",
            ),
            TvSettingRow(
                title = "About",
                subtitle = "Torve TV build",
            ),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, top = 20.dp, end = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            val requester = remember("pairing_header") { FocusRequester() }
            onFirstContentRequester(requester)
            val pairingSubtitle = when {
                syncState.isAuthenticated -> "Paired"
                syncState.pairingCode != null -> "Pairing code ${syncState.pairingCode?.code}"
                syncState.isLoading -> "Generating pairing code"
                else -> "Not paired"
            }
            TvSettingCard(
                title = "TV Pairing",
                subtitle = pairingSubtitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = { syncCoordinator.startTvPairingFlow() },
            )
        }

        items(rows) { row ->
            val requester = remember(row.title) { FocusRequester() }
            TvSettingCard(
                title = row.title,
                subtitle = row.subtitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { left = railFocusRequester },
                focusRequester = requester,
                onFocused = { onContentFocused(requester) },
                onClick = row.onClick,
            )
        }

        if (syncState.recentEvents.isNotEmpty()) {
            item {
                Text(
                    text = "Realtime Debug",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFDBE2F0),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(syncState.recentEvents, key = { it }) { entry ->
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xCCDBE2F0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x221B2434), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        syncState.error?.let { err ->
            item {
                Text(
                    text = err,
                    color = Color(0xFFFFB8B8),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TvSettingCard(
    title: String,
    subtitle: String,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.02f else 1f, label = "settingsScale")

    Column(
        modifier = modifier
            .scale(scale)
            .background(
                color = if (focused) Color(0x2A1E2B3F) else Color(0x1A1A2232),
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = 1.5.dp,
                color = if (focused) Color(0xFFC7A66E) else Color(0x334D5B72),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .focusRequester(focusRequester)
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xCCDBE2F0),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

