package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.data.auth.AuthClient
import com.torve.data.auth.UserIdProvider
import com.torve.data.profile.*
import com.torve.data.simkl.*
import com.torve.presentation.integrations.AutomationSettingsViewModel
import com.torve.presentation.profile.ProfileViewModel
import com.torve.presentation.settings.*
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MobileConnectionCenterScreen(onBack: () -> Unit, onRepair: (String) -> Unit, onProfiles: () -> Unit, onAccount: () -> Unit,
    onProviderNavigate: ((String) -> Unit)? = null, onDiagnostics: () -> Unit = {},
    settings: SettingsViewModel = koinInject(), profiles: ProfileViewModel = koinInject(),
    household: HouseholdSyncService = koinInject(), simkl: SimklSyncService = koinInject(), auth: AuthClient = koinInject(),
    userIds: UserIdProvider = koinInject(), automation: AutomationSettingsViewModel = koinInject(),
    health: com.torve.presentation.providerhealth.ProviderHealthCoordinator = koinInject()) {
    val settingsState by settings.state.collectAsState()
    val profileState by profiles.state.collectAsState()
    val householdState by household.state.collectAsState()
    val simklState by simkl.state.collectAsState()
    val account by auth.authUserFlow.collectAsState()
    val automationState by automation.state.collectAsState()
    val healthEntries by health.entries.collectAsState()
    val person = userIds.currentDataUserId()
    val currentSettings = settingsState.takeIf { it.personalDataUserId == person } ?: SettingsUiState()
    val currentHousehold = householdState.takeIf { it.personalDataUserId == person } ?: HouseholdSyncState()
    val currentSimkl = simklState.takeIf { it.personalDataUserId == person } ?: SimklSyncState()
    LaunchedEffect(person) { settings.refreshSettings(); profiles.loadProfiles() }
    val readiness = buildIntegrationReadinessSummary(currentSettings, automationState.instances, healthEntries)
    val plan = connectionSetupPlan(readiness)
    var showSync by remember(person) { mutableStateOf(false) }
    var showWorkflows by remember(person) { mutableStateOf(false) }
    ProductPage(stringResource(R.string.gap_connections_title), stringResource(R.string.gap_connections_detail), onBack) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("profile") { ProductToolRow(localizedUiText(profileState.activeProfile?.name ?: "Default profile"), localizedUiText("Active household profile"), Icons.Rounded.Person, onProfiles, selected = true) }
            item("services_heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.gap_connections_services), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { health.runAll() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.gap_check_connections))
                    }
                }
            }
            item("services") {
                com.torve.android.ui.settings.ProviderStatusSection(
                    onConfigure = { entry ->
                        com.torve.android.ui.transfer.providerSettingsRouteFor(entry)?.let { route ->
                            onProviderNavigate?.invoke(route) ?: onRepair(integrationRepairDestination(
                                when (entry.category) {
                                    com.torve.domain.providerhealth.ProviderHealthCategory.TRAKT,
                                    com.torve.domain.providerhealth.ProviderHealthCategory.SIMKL -> IntegrationWorkflow.TRACKING
                                    com.torve.domain.providerhealth.ProviderHealthCategory.REQUEST_MANAGER -> IntegrationWorkflow.SAVE_TO_LIBRARY
                                    com.torve.domain.providerhealth.ProviderHealthCategory.PLEX_JELLYFIN -> IntegrationWorkflow.PERSONAL_LIBRARY
                                    else -> IntegrationWorkflow.WATCH_NOW
                                }, currentSettings, listOf(entry)).routeKey)
                        }
                    }, onDiagnose = { onDiagnostics() }, coordinator = health)
            }
            if (!plan.playbackConfigured) item("playback_plan") { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(localizedUiText(plan.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(plan.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!plan.playbackConfigured) {
                        ProductToolRow(localizedUiText("Streaming sources"), localizedUiText("Connect an add-on or streaming provider"), Icons.Rounded.PlayCircleOutline, { onRepair("streaming_setup") })
                        Text(localizedUiText("My media server"), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onRepair("jellyfin") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(localizedUiText("Jellyfin")) }
                            OutlinedButton(onClick = { onRepair("plex") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(localizedUiText("Plex")) }
                        }
                    }
                }
            } }
            item("sync_heading") {
                ProductToolRow(stringResource(R.string.gap_connections_sync),
                    stringResource(R.string.gap_connections_detail),
                    if (showSync) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, { showSync = !showSync })
            }
            if (showSync) {
            item("torve") { MobileSyncStatusCard(localizedUiText("Torve household"), account != null, currentHousehold.syncing, currentHousehold.lastSyncedAt,
                currentHousehold.pendingChanges, currentHousehold.error, { household.requestRefresh(force = true) }, onAccount,
                localizedUiText(if (account == null) "Sign in to share your watched episodes and library across devices." else "Watched episodes, progress, lists and profiles.")) }
            item("trakt") { MobileSyncStatusCard(localizedUiText("Trakt"), currentSettings.traktConnected, currentSettings.traktSyncing, currentSettings.traktLastSyncTime,
                currentSettings.traktPendingWrites, currentSettings.traktError, settings::syncTraktNow, { onRepair("trakt") }, localizedUiText("Watch history, ratings and your Trakt library.")) }
            item("simkl") { MobileSyncStatusCard(localizedUiText("SIMKL"), currentSettings.simklConnected, currentSimkl.running || currentSettings.simklLoading, currentSimkl.lastSyncedAt,
                0, currentSimkl.error ?: currentSettings.simklError, settings::checkSimklConnection, { onRepair("simkl") },
                localizedUiText(if (currentSimkl.unresolvedTitles > 0) "${currentSimkl.unresolvedTitles} titles still need a catalog match." else "Your SIMKL movies, shows and anime.")) }
            }
            item("setup_heading") {
                ProductToolRow(stringResource(R.string.gap_connections_workflows),
                    stringResource(R.string.gap_connections_detail),
                    if (showWorkflows) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, { showWorkflows = !showWorkflows })
            }
            if (showWorkflows) items(readiness.items, key = { it.workflow.name }) { workflow ->
                val repair = integrationRepairDestination(workflow.workflow, currentSettings, healthEntries).routeKey
                ProductToolRow(workflow.title, "${workflow.statusLabel} · ${workflow.detail}", when (workflow.status) {
                    IntegrationReadinessStatus.READY -> Icons.Rounded.CheckCircleOutline
                    IntegrationReadinessStatus.NEEDS_ATTENTION -> Icons.Rounded.ErrorOutline
                    IntegrationReadinessStatus.NOT_CONFIGURED -> Icons.Rounded.AddCircleOutline
                }, { onRepair(repair) })
            }
            item("note") { Text(localizedUiText("Readiness uses your configured services and the latest available connection checks. Sync results above show the last successful transfer. Only the service you select is refreshed."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) }
        }
    }
}

@Composable
internal fun MobileSyncStatusCard(title: String, connected: Boolean, busy: Boolean, lastSuccess: Long?, pending: Int,
    error: String?, onSync: () -> Unit, onRepair: () -> Unit, description: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("sync_${title.lowercase(Locale.ROOT).replace(' ', '_')}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localizedUiText(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (error != null) Icon(Icons.Rounded.ErrorOutline, contentDescription = localizedUiText("Needs attention"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
            Text(localizedUiText(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(localizedUiText(when {
                busy -> "Syncing this service…"
                !connected -> "Not connected"
                lastSuccess == null -> "No successful sync recorded yet"
                else -> "Last synced ${formatSyncTime(lastSuccess)}"
            }), style = MaterialTheme.typography.bodyMedium)
            if (pending > 0) Text(localizedUiText("$pending changes waiting to sync"), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(localizedUiText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) FilledTonalButton(onClick = onSync, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("sync_action_$title")) {
                    Text(localizedUiText(if (busy) "Syncing…" else if (error != null || pending > 0) "Retry sync" else "Sync now"))
                }
                TextButton(onClick = onRepair, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(localizedUiText(if (connected) "Manage connection" else "Connect")) }
            }
        }
    }
}

internal fun formatSyncTime(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))
