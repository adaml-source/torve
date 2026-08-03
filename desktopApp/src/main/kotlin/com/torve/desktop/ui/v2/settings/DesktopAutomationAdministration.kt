@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.torve.desktop.ui.v2.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveFilterChip
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorveListRow
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.integrations.AutomationCapability
import com.torve.domain.integrations.AutomationInstanceRole
import com.torve.domain.integrations.AutomationIndexerCreateRequest
import com.torve.domain.integrations.AutomationIndexerProtocol
import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.IntegrationStorageMode
import com.torve.domain.integrations.TdarrRunAutomationRequest
import com.torve.domain.integrations.TdarrJob
import com.torve.domain.integrations.TdarrJobAction
import com.torve.domain.integrations.TdarrJobActionRequest
import com.torve.domain.integrations.TdarrScanRequest
import com.torve.domain.integrations.TdarrWorkerAction
import com.torve.domain.integrations.TdarrWorkerLimitChange
import com.torve.domain.integrations.TdarrWorkerLimitRequest
import com.torve.presentation.integrations.AutomationAdminSection
import com.torve.presentation.integrations.AutomationAdministrationUiState
import com.torve.presentation.integrations.AutomationAdministrationViewModel
import com.torve.presentation.integrations.AutomationConfirmationKind
import com.torve.presentation.integrations.AutomationSettingsViewModel
import org.koin.mp.KoinPlatform

@Composable
internal fun DesktopAutomationSection() {
    val settingsViewModel = remember {
        KoinPlatform.getKoin().get<AutomationSettingsViewModel>()
    }
    val administrationViewModel = remember {
        KoinPlatform.getKoin().get<AutomationAdministrationViewModel>()
    }
    val settings by settingsViewModel.state.collectAsState()
    val admin by administrationViewModel.state.collectAsState()
    var profileIndex by remember { mutableIntStateOf(0) }
    var rootIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(settings.instances) {
        administrationViewModel.reloadInstances()
    }
    LaunchedEffect(admin.selectedInstanceId, admin.qualityProfiles.size, admin.rootFolders.size) {
        profileIndex = profileIndex.coerceIn(0, (admin.qualityProfiles.size - 1).coerceAtLeast(0))
        rootIndex = rootIndex.coerceIn(0, (admin.rootFolders.size - 1).coerceAtLeast(0))
    }

    AutomationConnectionsCard(settings, settingsViewModel)

    TorveSectionCard(
        title = "*Arr media automation",
        supportingText = "The same search, queue, indexer, subtitle and Tdarr controls used by Torve mobile and TV.",
    ) {
        if (admin.instances.isEmpty()) {
            Text("Save a connection above to unlock administration.", color = TorveDesktopThemeTokens.colors.textSecondary)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                admin.instances.forEach { instance ->
                    TorveFilterChip(
                        text = instance.name,
                        selected = admin.selectedInstanceId == instance.id,
                        onClick = { administrationViewModel.selectInstance(instance.id) },
                    )
                }
                TorveGhostButton(text = "Refresh", onClick = administrationViewModel::refresh, enabled = !admin.isRefreshing)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                desktopSections(admin).forEach { section ->
                    TorveFilterChip(
                        text = section.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = admin.section == section,
                        onClick = { administrationViewModel.selectSection(section) },
                    )
                }
            }
            if (admin.isRefreshing || admin.busyAction != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(admin.busyAction ?: "Refreshing automation status", color = TorveDesktopThemeTokens.colors.accent)
            }
            admin.message?.let { TorveBanner("Completed", it, tone = TorveBannerTone.Success) }
            admin.error?.let { TorveBanner("Automation action", it, tone = TorveBannerTone.Error) }

            when (admin.section) {
                AutomationAdminSection.LIBRARY -> DesktopLibraryAdmin(
                    state = admin,
                    viewModel = administrationViewModel,
                    profileIndex = profileIndex,
                    rootIndex = rootIndex,
                    onNextProfile = { if (admin.qualityProfiles.isNotEmpty()) profileIndex = (profileIndex + 1) % admin.qualityProfiles.size },
                    onNextRoot = { if (admin.rootFolders.isNotEmpty()) rootIndex = (rootIndex + 1) % admin.rootFolders.size },
                )
                AutomationAdminSection.RELEASES -> DesktopReleaseAdmin(admin, administrationViewModel)
                AutomationAdminSection.QUEUE -> DesktopQueueAdmin(admin, administrationViewModel)
                AutomationAdminSection.INDEXERS -> DesktopIndexerAdmin(admin, administrationViewModel)
                AutomationAdminSection.SUBTITLES -> DesktopSubtitleAdmin(admin, administrationViewModel)
                AutomationAdminSection.TDARR -> DesktopTdarrAdmin(admin, administrationViewModel)
            }
        }
    }

    admin.pendingConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = administrationViewModel::dismissConfirmation,
            title = { Text(confirmation.title) },
            text = { Text(confirmation.description) },
            dismissButton = {
                TextButton(onClick = administrationViewModel::dismissConfirmation) { Text("Cancel") }
            },
            confirmButton = {
                Row {
                    if (confirmation.kind == AutomationConfirmationKind.REMOVE_QUEUE_ITEM) {
                        TextButton(onClick = { administrationViewModel.confirmPendingAction(true) }) {
                            Text("Blocklist and search")
                        }
                    }
                    TextButton(onClick = { administrationViewModel.confirmPendingAction() }) { Text("Confirm") }
                }
            },
        )
    }
}

@Composable
private fun AutomationConnectionsCard(
    state: com.torve.presentation.integrations.AutomationSettingsUiState,
    viewModel: AutomationSettingsViewModel,
) {
    TorveSectionCard(
        title = "Sonarr, Radarr, Prowlarr, Bazarr and Tdarr",
        supportingText = "Connections can be encrypted with your Torve account for automatic restore, or kept only on this computer.",
    ) {
        if (state.instances.any { it.storageMode == IntegrationStorageMode.DEVICE_ONLY }) {
            TorveSecondaryButton(
                text = "Sync all existing connections with my account",
                onClick = viewModel::syncAllWithAccount,
                enabled = !state.isBusy,
            )
        }
        state.instances.forEach { instance ->
            TorveListRow(
                title = instance.name,
                subtitle = buildString {
                    append(instance.serviceType.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (instance.serviceType == AutomationServiceType.SONARR || instance.serviceType == AutomationServiceType.RADARR) {
                        append(" · ")
                        append(if (instance.role == AutomationInstanceRole.UHD) "4K" else "Standard")
                    }
                    append(" · ")
                    append(if (instance.storageMode == IntegrationStorageMode.ACCOUNT) "Account synced" else "This computer only")
                },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TorveSecondaryButton("Edit", { viewModel.edit(instance) })
                        TorveGhostButton("Remove", { viewModel.remove(instance) })
                    }
                },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomationServiceType.entries.forEach { service ->
                TorveFilterChip(
                    text = service.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = state.serviceType == service,
                    onClick = { viewModel.selectService(service) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorveTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = "Connection name",
                modifier = Modifier.weight(1f),
            )
            TorveTextField(
                value = state.serverUrl,
                onValueChange = viewModel::updateServerUrl,
                label = "Server URL",
                modifier = Modifier.weight(2f),
            )
        }
        TorveTextField(
            value = state.apiKey,
            onValueChange = viewModel::updateApiKey,
            label = if (state.serviceType == AutomationServiceType.TDARR) "Optional API key" else "API key",
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Text("Connection storage", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TorveFilterChip(
                text = "Sync with my account",
                selected = state.storageMode == IntegrationStorageMode.ACCOUNT,
                onClick = { viewModel.selectStorageMode(IntegrationStorageMode.ACCOUNT) },
            )
            TorveFilterChip(
                text = "Only on this computer",
                selected = state.storageMode == IntegrationStorageMode.DEVICE_ONLY,
                onClick = { viewModel.selectStorageMode(IntegrationStorageMode.DEVICE_ONLY) },
            )
        }
        Text(
            if (state.storageMode == IntegrationStorageMode.ACCOUNT) {
                "The URL and API key are encrypted in your Torve account and restored on signed-in devices."
            } else {
                "The URL and API key never leave this computer."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TorveDesktopThemeTokens.colors.textSecondary,
        )
        if (state.serviceType == AutomationServiceType.SONARR || state.serviceType == AutomationServiceType.RADARR) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomationInstanceRole.entries.forEach { role ->
                    TorveFilterChip(
                        text = if (role == AutomationInstanceRole.UHD) "4K" else "Standard",
                        selected = state.role == role,
                        onClick = { viewModel.selectRole(role) },
                    )
                }
                Row {
                    Checkbox(checked = state.isDefault, onCheckedChange = viewModel::setDefault)
                    Text("Default for this quality role", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorvePrimaryButton("Save and test", viewModel::saveAndTest, enabled = !state.isBusy)
            if (state.editingId != null) TorveGhostButton("Cancel edit", viewModel::cancelEdit)
        }
        state.message?.let { TorveBanner("Connection", it, tone = TorveBannerTone.Success) }
        state.error?.let { TorveBanner("Connection", it, tone = TorveBannerTone.Error) }
        if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DesktopLibraryAdmin(
    state: AutomationAdministrationUiState,
    viewModel: AutomationAdministrationViewModel,
    profileIndex: Int,
    rootIndex: Int,
    onNextProfile: () -> Unit,
    onNextRoot: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TorveTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateSearchQuery,
            label = "Find a movie or series",
            modifier = Modifier.weight(1f),
            onSubmit = viewModel::lookupMedia,
        )
        TorvePrimaryButton("Search", viewModel::lookupMedia)
    }
    if (state.lookupResults.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorveSecondaryButton("Quality: ${state.qualityProfiles.getOrNull(profileIndex)?.name ?: "Unavailable"}", onNextProfile)
            TorveSecondaryButton("Folder: ${state.rootFolders.getOrNull(rootIndex)?.path ?: "Unavailable"}", onNextRoot)
        }
    }
    state.lookupResults.forEach { item ->
        DesktopMediaRow(item, "Add") {
            viewModel.addMedia(
                item,
                state.qualityProfiles.getOrNull(profileIndex)?.id ?: 0,
                state.rootFolders.getOrNull(rootIndex)?.path.orEmpty(),
            )
        }
    }
    if (state.library.isNotEmpty()) Text("Managed library (${state.library.size})", fontWeight = FontWeight.SemiBold)
    state.library.forEach { item -> DesktopMediaRow(item, "Find releases") { viewModel.searchReleases(item.id) } }
}

@Composable
private fun DesktopMediaRow(item: AutomationLibraryItem, action: String, onClick: () -> Unit) {
    TorveListRow(
        title = item.title,
        subtitle = listOfNotNull(item.year?.toString(), item.kind.name.lowercase(), if (item.hasFile) "downloaded" else null).joinToString(" · "),
        trailing = { TorveSecondaryButton(action, onClick) },
    )
}

@Composable
private fun DesktopReleaseAdmin(state: AutomationAdministrationUiState, viewModel: AutomationAdministrationViewModel) {
    if (state.releases.isEmpty()) Text("Choose Find releases from the Library tab.", color = TorveDesktopThemeTokens.colors.textSecondary)
    state.releases.forEach { release ->
        TorveListRow(
            title = release.title,
            subtitle = listOfNotNull(
                release.quality,
                release.indexer,
                release.seeders?.let { "$it seeders" },
                release.sizeBytes?.let(::desktopBytes),
                release.rejections.takeIf { it.isNotEmpty() }?.joinToString(),
            ).joinToString(" · "),
            trailing = { TorvePrimaryButton("Grab", { viewModel.grabRelease(release) }) },
        )
    }
}

@Composable
private fun DesktopQueueAdmin(state: AutomationAdministrationUiState, viewModel: AutomationAdministrationViewModel) {
    if (state.queue.isEmpty()) Text("The acquisition queue is empty.", color = TorveDesktopThemeTokens.colors.textSecondary)
    state.queue.forEach { item ->
        TorveListRow(
            title = item.title,
            subtitle = listOfNotNull(item.status, item.progressPercent?.let { "${it.toInt()}%" }, item.timeLeft, item.errorMessage).joinToString(" · "),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveSecondaryButton("Retry", { viewModel.retryQueueItem(item) })
                    TorveGhostButton("Remove", { viewModel.requestRemoveQueueItem(item) })
                }
            },
        )
    }
}

@Composable
private fun DesktopIndexerAdmin(state: AutomationAdministrationUiState, viewModel: AutomationAdministrationViewModel) {
    var name by remember { androidx.compose.runtime.mutableStateOf("") }
    var baseUrl by remember { androidx.compose.runtime.mutableStateOf("") }
    var apiPath by remember { androidx.compose.runtime.mutableStateOf("/api") }
    var indexerApiKey by remember { androidx.compose.runtime.mutableStateOf("") }
    var protocol by remember { androidx.compose.runtime.mutableStateOf(AutomationIndexerProtocol.TORRENT) }
    Text("Add generic indexer", fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomationIndexerProtocol.entries.forEach { choice ->
            TorveFilterChip(
                text = if (choice == AutomationIndexerProtocol.TORRENT) "Torznab" else "Newznab",
                selected = protocol == choice,
                onClick = { protocol = choice },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TorveTextField(name, { name = it }, "Name", Modifier.weight(1f))
        TorveTextField(baseUrl, { baseUrl = it }, "Base URL", Modifier.weight(2f))
        TorveTextField(apiPath, { apiPath = it }, "API path", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TorveTextField(
            indexerApiKey,
            { indexerApiKey = it },
            "Indexer API key",
            Modifier.weight(1f),
            visualTransformation = PasswordVisualTransformation(),
        )
        TorvePrimaryButton(
            "Add and enable",
            {
                viewModel.createIndexer(
                    AutomationIndexerCreateRequest(name, protocol, baseUrl, apiPath, indexerApiKey),
                )
            },
            enabled = name.isNotBlank() && baseUrl.isNotBlank(),
        )
    }
    if (state.indexers.isEmpty()) Text("No indexers are attached.", color = TorveDesktopThemeTokens.colors.textSecondary)
    state.indexers.forEach { indexer ->
        TorveListRow(
            title = indexer.name,
            subtitle = listOfNotNull(indexer.implementation, indexer.protocol, if (indexer.enabled) "enabled" else "disabled", indexer.status).joinToString(" · "),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorveSecondaryButton("Test", { viewModel.testIndexer(indexer) })
                    TorveSecondaryButton(if (indexer.enabled) "Disable" else "Enable", { viewModel.setIndexerEnabled(indexer, !indexer.enabled) })
                    TorveGhostButton("Delete", { viewModel.requestDeleteIndexer(indexer) })
                }
            },
        )
    }
}

@Composable
private fun DesktopSubtitleAdmin(state: AutomationAdministrationUiState, viewModel: AutomationAdministrationViewModel) {
    if (state.wantedSubtitles.isEmpty()) Text("Bazarr has no wanted subtitles.", color = TorveDesktopThemeTokens.colors.textSecondary)
    state.wantedSubtitles.forEach { target ->
        TorveListRow(
            title = target.title,
            subtitle = "Missing: ${target.missingLanguages.joinToString().ifBlank { "configured languages" }}",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TorvePrimaryButton("Search", { viewModel.searchSubtitles(target) })
                    TorveSecondaryButton("Auto", { viewModel.searchMissingSubtitle(target, target.missingLanguages.firstOrNull().orEmpty()) })
                }
            },
        )
    }
    val target = state.selectedSubtitleTarget
    if (target != null) {
        state.subtitleCandidates.forEach { candidate ->
            TorveListRow(
                title = "${candidate.language} · ${candidate.provider}",
                subtitle = listOfNotNull(candidate.score?.let { "Score ${it.toInt()}" }, candidate.release).joinToString(" · "),
                trailing = { TorvePrimaryButton("Download", { viewModel.downloadSubtitle(target, candidate) }) },
            )
        }
    }
}

@Composable
private fun DesktopTdarrAdmin(state: AutomationAdministrationUiState, viewModel: AutomationAdministrationViewModel) {
    val overview = state.tdarr
    if (overview == null) {
        Text("Refresh to load Tdarr libraries, nodes and jobs.", color = TorveDesktopThemeTokens.colors.textSecondary)
        return
    }
    Text("Libraries", fontWeight = FontWeight.SemiBold)
    overview.libraries.forEach { library ->
        TorveListRow(
            title = library.name,
            subtitle = library.sourcePath.orEmpty(),
            trailing = { TorvePrimaryButton("Scan", { viewModel.scanTdarrLibrary(TdarrScanRequest(library.id)) }) },
        )
    }
    Text("Automations", fontWeight = FontWeight.SemiBold)
    overview.automations.forEach { automation ->
        TorveListRow(
            title = automation.name,
            subtitle = if (automation.enabled) "Enabled" else "Manual",
            trailing = { TorvePrimaryButton("Run", { viewModel.runTdarrAutomation(TdarrRunAutomationRequest(automation.id, executeImmediately = true)) }) },
        )
    }
    Text("Nodes and active workers", fontWeight = FontWeight.SemiBold)
    overview.nodes.forEach { node ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TorveListRow(
                title = node.name,
                subtitle = if (node.online) "Online · ${node.workers.size} active workers" else "Offline",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TorveGhostButton("CPU −", {
                            viewModel.changeTdarrWorkerLimit(TdarrWorkerLimitRequest(node.id, "transcodecpu", TdarrWorkerLimitChange.DECREASE))
                        })
                        TorveSecondaryButton("CPU +", {
                            viewModel.changeTdarrWorkerLimit(TdarrWorkerLimitRequest(node.id, "transcodecpu", TdarrWorkerLimitChange.INCREASE))
                        })
                    }
                },
            )
            node.workers.forEach { worker ->
                TorveListRow(
                    title = worker.file?.substringAfterLast('/')?.substringAfterLast('\\') ?: worker.workerId,
                    subtitle = "${worker.workerType} · ${worker.status}",
                    modifier = Modifier.padding(start = 24.dp),
                    trailing = { TorveGhostButton("Cancel", { viewModel.requestCancelTdarrWorker(TdarrWorkerAction(node.id, worker.workerId)) }) },
                )
            }
        }
    }
    Text("Jobs (${overview.jobs.size})", fontWeight = FontWeight.SemiBold)
    overview.jobs.take(100).forEach { job ->
        TorveListRow(
            title = job.file.substringAfterLast('/').substringAfterLast('\\'),
            subtitle = listOfNotNull(job.status, job.progressPercent?.let { "${it.toInt()}%" }).joinToString(" · "),
            trailing = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    desktopTdarrJobActions(job).forEach { action ->
                        TorveGhostButton(action.name.lowercase().replaceFirstChar { it.uppercase() }, {
                            viewModel.actOnTdarrJob(TdarrJobActionRequest(job.id, action))
                        })
                    }
                }
            },
        )
    }
}

private fun desktopTdarrJobActions(job: TdarrJob): List<TdarrJobAction> = when (job.status) {
    "transcodeSuccess", "conditionsMet" -> listOf(TdarrJobAction.ACCEPT, TdarrJobAction.REQUEUE, TdarrJobAction.SKIP)
    "copyFailed", "transcodeError", "transcodeCancelled" -> listOf(TdarrJobAction.RETRY, TdarrJobAction.ACCEPT, TdarrJobAction.REQUEUE, TdarrJobAction.SKIP)
    "requireReview" -> listOf(TdarrJobAction.REVIEWED, TdarrJobAction.SKIP)
    else -> emptyList()
}

private fun desktopSections(state: AutomationAdministrationUiState): List<AutomationAdminSection> = buildList {
    if (AutomationCapability.LIBRARY_LOOKUP in state.capabilities) add(AutomationAdminSection.LIBRARY)
    if (AutomationCapability.RELEASE_SEARCH in state.capabilities) add(AutomationAdminSection.RELEASES)
    if (AutomationCapability.QUEUE_READ in state.capabilities) add(AutomationAdminSection.QUEUE)
    if (AutomationCapability.INDEXER_READ in state.capabilities) add(AutomationAdminSection.INDEXERS)
    if (AutomationCapability.SUBTITLE_WANTED in state.capabilities) add(AutomationAdminSection.SUBTITLES)
    if (AutomationCapability.TDARR_LIBRARIES in state.capabilities) add(AutomationAdminSection.TDARR)
}

private fun desktopBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) "${(gib * 10).toInt() / 10.0} GB" else "${bytes / (1024 * 1024)} MB"
}
