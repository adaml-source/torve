package com.torve.android.tv.components

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.settings.IntegrationReadinessItem
import com.torve.presentation.settings.IntegrationReadinessStatus
import com.torve.presentation.settings.IntegrationReadinessSummary
import com.torve.presentation.settings.IntegrationWorkflow
import com.torve.presentation.settings.SettingsUiState

enum class TvConnectionCenterAction {
    SYNC_TRAKT, RECONNECT_TRAKT, CHECK_SIMKL, CONNECT_SIMKL,
    OPEN_STREAMING_SETUP, OPEN_ADDONS, RECEIVE_CREDENTIALS,
}

@Composable
fun TvConnectionCenterDialog(
    summary: IntegrationReadinessSummary,
    settings: SettingsUiState,
    onDismiss: () -> Unit,
    onOpenSettings: (IntegrationWorkflow) -> Unit,
    onAction: (TvConnectionCenterAction) -> Unit = {},
) {
    val requesters = remember { IntegrationWorkflow.entries.associateWith { FocusRequester() } }
    val firstRequester = requesters.getValue(summary.items.first().workflow)
    val actionRequester = remember { FocusRequester() }
    var selected by remember { mutableStateOf(summary.items.first().workflow) }
    val item = summary.item(selected)
    TvDocumentModal(localizedUiText("Connection center"), firstRequester, onDismiss) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(com.torve.presentation.settings.connectionSetupPlan(summary).detail,
                color = Silver, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary.items.forEachIndexed { index, entry ->
                        key(entry.workflow) {
                            val requester = requesters.getValue(entry.workflow)
                            var focused by remember { mutableStateOf(false) }
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Charcoal, RoundedCornerShape(12.dp))
                                    .border(2.dp, if (focused) Amber else Steel, RoundedCornerShape(12.dp))
                                    .focusRequester(requester)
                                    .focusProperties {
                                        right = actionRequester
                                        up = summary.items.getOrNull(index - 1)?.let { requesters.getValue(it.workflow) } ?: requester
                                        down = summary.items.getOrNull(index + 1)?.let { requesters.getValue(it.workflow) } ?: requester
                                    }
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        if (it.isFocused) {
                                            selected = entry.workflow
                                        }
                                    }
                                    .clickable {
                                        selected = entry.workflow
                                        actionRequester.requestFocus()
                                    }
                                    .padding(14.dp),
                            ) {
                                Text(localizedUiText(entry.title), color = Snow, style = MaterialTheme.typography.titleSmall)
                                Text(entry.statusLabel, style = MaterialTheme.typography.labelMedium, color = when (entry.status) {
                                    IntegrationReadinessStatus.READY -> Emerald
                                    IntegrationReadinessStatus.NEEDS_ATTENTION -> Amber
                                    IntegrationReadinessStatus.NOT_CONFIGURED -> Silver
                                })
                            }
                        }
                    }
                }
                key(selected) {
                    Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(localizedUiText(item.title), color = Snow, style = MaterialTheme.typography.titleMedium)
                        Text(item.detail, color = Snow, style = MaterialTheme.typography.bodyMedium)
                        if (selected == IntegrationWorkflow.TRACKING) {
                            TvTrackingConnectionStatus(settings)
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selected == IntegrationWorkflow.WATCH_NOW) {
                            TvStreamingConnectionActions(actionRequester, requesters.getValue(selected), onAction,
                                { onOpenSettings(selected) })
                        } else if (selected == IntegrationWorkflow.TRACKING) {
                            TvTrackingConnectionActions(
                                settings = settings,
                                firstRequester = actionRequester,
                                workflowRequester = requesters.getValue(selected),
                                onAction = onAction,
                                onOpenSettings = { onOpenSettings(selected) },
                            )
                        } else {
                            Text(connectionCenterDetails(item, settings), color = Silver, style = MaterialTheme.typography.bodySmall)
                            TvConnectionCenterButton(
                                text = localizedUiText(when (selected) {
                                IntegrationWorkflow.WATCH_NOW -> "Open streaming setup"
                                IntegrationWorkflow.PERSONAL_LIBRARY -> "Open media-server settings"
                                IntegrationWorkflow.SAVE_TO_LIBRARY -> "Open library request settings"
                                IntegrationWorkflow.AUTOMATION -> "Open automation settings"
                                IntegrationWorkflow.TRACKING -> "Open tracking settings"
                                }),
                                requester = actionRequester,
                                modifier = Modifier.focusProperties { left = requesters.getValue(selected) },
                                onClick = { onOpenSettings(selected) },
                            )
                        }
                        }
                    }
                }
            }
            Text(localizedUiText("Connection status reflects the latest check. Sync history and repair actions are shown for each service. Press Back to return."),
                color = Silver, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TvTrackingConnectionStatus(settings: SettingsUiState) {
    val lastSync = settings.traktLastSyncTime?.let { time ->
        "Last successful sync: ${DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
    } ?: "No successful Trakt sync yet."
    Text(lastSync, color = Silver, style = MaterialTheme.typography.bodySmall)
    if (settings.traktPendingWrites > 0) {
        Text(localizedUiText("${settings.traktPendingWrites} change${if (settings.traktPendingWrites == 1) "" else "s"} waiting to upload"),
            color = Amber, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TvStreamingConnectionActions(first: FocusRequester, workflow: FocusRequester,
    onAction: (TvConnectionCenterAction) -> Unit, onCredentials: () -> Unit) {
    val addons = remember { FocusRequester() }
    val receive = remember { FocusRequester() }
    val credentials = remember { FocusRequester() }
    TvConnectionCenterButton(localizedUiText("Streaming sources"), first, Modifier.focusProperties { left = workflow; up = first; down = addons },
        onClick = { onAction(TvConnectionCenterAction.OPEN_STREAMING_SETUP) })
    TvConnectionCenterButton(localizedUiText("Installed add-ons"), addons, Modifier.focusProperties { left = workflow; up = first; down = receive },
        onClick = { onAction(TvConnectionCenterAction.OPEN_ADDONS) })
    TvConnectionCenterButton(localizedUiText("Receive from another device"), receive, Modifier.focusProperties { left = workflow; up = addons; down = credentials },
        onClick = { onAction(TvConnectionCenterAction.RECEIVE_CREDENTIALS) })
    TvConnectionCenterButton(localizedUiText("Provider credentials"), credentials, Modifier.focusProperties { left = workflow; up = receive; down = credentials }, onClick = onCredentials)
}

@Composable
private fun TvTrackingConnectionActions(
    settings: SettingsUiState,
    firstRequester: FocusRequester,
    workflowRequester: FocusRequester,
    onAction: (TvConnectionCenterAction) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val reconnectRequester = remember { FocusRequester() }
    val simklRequester = remember { FocusRequester() }
    val settingsRequester = remember { FocusRequester() }
    val traktBusy = settings.traktSyncing || settings.traktLoading || settings.isPollingTrakt
    val simklBusy = settings.simklLoading || settings.isPollingSimkl
    val hasTraktAccount = settings.traktConnected || settings.traktAccessToken.isNotBlank()
    TvConnectionCenterButton(
        text = localizedUiText(when {
            settings.traktSyncing -> "Syncing Trakt…"
            traktBusy -> "Checking Trakt…"
            !hasTraktAccount -> "Connect Trakt"
            !settings.traktConnected || !settings.traktError.isNullOrBlank() || settings.traktPendingWrites > 0 -> "Retry Trakt sync"
            else -> "Sync Trakt"
        }),
        requester = firstRequester,
        busy = traktBusy,
        modifier = Modifier.focusProperties {
            left = workflowRequester
            up = firstRequester
            down = if (hasTraktAccount) reconnectRequester else simklRequester
        },
        onClick = { onAction(if (hasTraktAccount) TvConnectionCenterAction.SYNC_TRAKT else TvConnectionCenterAction.RECONNECT_TRAKT) },
    )
    if (hasTraktAccount) {
        TvConnectionCenterButton(
            text = localizedUiText("Reconnect Trakt"),
            requester = reconnectRequester,
            busy = traktBusy,
            modifier = Modifier.focusProperties { left = workflowRequester; up = firstRequester; down = simklRequester },
            onClick = { onAction(TvConnectionCenterAction.RECONNECT_TRAKT) },
        )
    }
    val verifySimkl = settings.simklConnected && settings.simklError.isNullOrBlank()
    TvConnectionCenterButton(
        text = localizedUiText(if (simklBusy) "Checking SIMKL…" else if (verifySimkl) "Check SIMKL" else if (settings.simklConnected) "Reconnect SIMKL" else "Connect SIMKL"),
        requester = simklRequester,
        busy = simklBusy,
        modifier = Modifier.focusProperties {
            left = workflowRequester
            up = if (hasTraktAccount) reconnectRequester else firstRequester
            down = settingsRequester
        },
        onClick = { onAction(if (verifySimkl) TvConnectionCenterAction.CHECK_SIMKL else TvConnectionCenterAction.CONNECT_SIMKL) },
    )
    TvConnectionCenterButton(
        text = localizedUiText("Open tracking settings"),
        requester = settingsRequester,
        modifier = Modifier.focusProperties { left = workflowRequester; up = simklRequester; down = settingsRequester },
        onClick = onOpenSettings,
    )
}

@Composable
private fun TvConnectionCenterButton(
    text: String,
    requester: FocusRequester,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = text,
        color = if (busy) Silver else Snow,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .tvGlassControl(focused, enabled = !busy, cornerRadius = 10.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            // A running operation keeps its focused control composed and usable
            // for Left/Back; another OK press must not start duplicate work.
            .clickable { if (!busy) onClick() }
            .semantics { if (busy) disabled() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

internal fun connectionCenterDetails(item: IntegrationReadinessItem, settings: SettingsUiState): String = when (item.workflow) {
    IntegrationWorkflow.WATCH_NOW -> buildList {
        add("Streaming add-on: ${if (settings.hasStreamAddon) "installed" else "not installed"}.")
        add("Enabled debrid providers: ${settings.connectedDebridProviders.size}.")
        if (settings.configuredDebridProviders.size > settings.connectedDebridProviders.size) {
            add("Some saved providers are disabled or need a connection check.")
        }
        add("Use streaming setup to configure providers, or install a streaming add-on in Settings > Library.")
    }.joinToString("\n\n")
    IntegrationWorkflow.PERSONAL_LIBRARY -> listOf(
        "Jellyfin: ${if (settings.jellyfinServerUrl.isBlank()) "server address missing" else if (settings.jellyfinApiKey.isBlank()) "API key missing" else if (!settings.jellyfinConnected) "saved; connection test needed" else if (item.status == IntegrationReadinessStatus.READY) "connected" else "saved; review the latest connection check"}.",
        "Plex: ${if (settings.plexConnected && item.status == IntegrationReadinessStatus.READY) "connected" else if (settings.plexAccessToken.isBlank()) "authorization needed" else "saved; connection test needed"}.",
        "Open media-server settings to complete setup and test the server.",
    ).joinToString("\n\n")
    IntegrationWorkflow.SAVE_TO_LIBRARY -> listOf(
        "Seerr server address: ${if (settings.seerrServerUrl.isBlank()) "missing" else "saved"}.",
        "Seerr API key: ${if (settings.seerrApiKey.isBlank()) "missing" else "saved"}.",
        "Connection test: ${if (item.status == IntegrationReadinessStatus.READY) "passed" else if (settings.seerrConnected) "needs attention" else "needed"}.",
        "Seerr sends movie and series requests to your library. Open library request settings, then save and test Seerr.",
    ).joinToString("\n\n")
    IntegrationWorkflow.TRACKING -> listOf(
        "Trakt: ${when { settings.traktSyncing -> "syncing"; settings.traktError != null -> "needs attention; open Trakt to verify or reconnect"; settings.traktConnected -> "authorized"; else -> "authorization needed" }}.",
        "SIMKL: ${if (settings.simklError != null) "needs attention; open SIMKL to reconnect" else if (settings.simklConnected) "authorized" else "authorization needed"}.",
        "Tracking keeps your watchlist, watched history and progress up to date. Trakt can be synced separately.",
    ).joinToString("\n\n")
    IntegrationWorkflow.AUTOMATION -> "${item.configuredCount} enabled automation services.\n\nAutomation manages requests and downloads through services such as Sonarr and Radarr. Configured means a setup is saved; open automation settings to test each connection."
}
