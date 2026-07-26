package com.torve.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.integrations.AutomationCapability
import com.torve.domain.integrations.AutomationIndexer
import com.torve.domain.integrations.AutomationIndexerCreateRequest
import com.torve.domain.integrations.AutomationIndexerProtocol
import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationQueueItem
import com.torve.domain.integrations.AutomationRelease
import com.torve.domain.integrations.AutomationSubtitleCandidate
import com.torve.domain.integrations.AutomationSubtitleTarget
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
import org.koin.compose.koinInject

@Composable
fun AutomationAdministrationScreen(
    onBack: () -> Unit,
    onManageConnections: () -> Unit = onBack,
    viewModel: AutomationAdministrationViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var profileIndex by remember { mutableIntStateOf(0) }
    var rootIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.selectedInstanceId, state.qualityProfiles.size, state.rootFolders.size) {
        profileIndex = profileIndex.coerceIn(0, (state.qualityProfiles.size - 1).coerceAtLeast(0))
        rootIndex = rootIndex.coerceIn(0, (state.rootFolders.size - 1).coerceAtLeast(0))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Snow)
            }
            Column(Modifier.weight(1f)) {
                Text("Automation administration", color = Snow, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Search, acquire, repair subtitles and control transcodes", color = Silver, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onManageConnections) {
                Text("Connections", color = Snow)
            }
            IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Amber, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Amber)
                }
            }
        }

        if (state.instances.isEmpty()) {
            AdminCard {
                Text("No automation services are configured on this device.", color = Snow)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onManageConnections, colors = adminButtonColors()) { Text("Add connection") }
            }
        } else {
            Text("Service", color = Silver, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.instances.forEach { instance ->
                    val selected = state.selectedInstanceId == instance.id
                    if (selected) {
                        Button(onClick = { viewModel.selectInstance(instance.id) }, colors = adminButtonColors()) {
                            Text(instance.name)
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.selectInstance(instance.id) }) {
                            Text(instance.name, color = Snow)
                        }
                    }
                }
            }

            val sections = supportedSections(state)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { section ->
                    val label = section.name.lowercase().replaceFirstChar(Char::uppercase)
                    if (state.section == section) {
                        Button(onClick = { viewModel.selectSection(section) }, colors = adminButtonColors()) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { viewModel.selectSection(section) }) { Text(label, color = Snow) }
                    }
                }
            }

            state.busyAction?.let { action ->
                AdminStatus(action, Amber, showSpinner = true)
            }
            state.message?.let { AdminStatus(it, Emerald) }
            state.error?.let { AdminStatus(it, Ruby) }

            when (state.section) {
                AutomationAdminSection.LIBRARY -> LibraryAdmin(
                    state = state,
                    viewModel = viewModel,
                    profileIndex = profileIndex,
                    rootIndex = rootIndex,
                    onNextProfile = { if (state.qualityProfiles.isNotEmpty()) profileIndex = (profileIndex + 1) % state.qualityProfiles.size },
                    onNextRoot = { if (state.rootFolders.isNotEmpty()) rootIndex = (rootIndex + 1) % state.rootFolders.size },
                )
                AutomationAdminSection.RELEASES -> ReleaseAdmin(state.releases, viewModel)
                AutomationAdminSection.QUEUE -> QueueAdmin(state.queue, viewModel)
                AutomationAdminSection.INDEXERS -> IndexerAdmin(state.indexers, viewModel)
                AutomationAdminSection.SUBTITLES -> SubtitleAdmin(state, viewModel)
                AutomationAdminSection.TDARR -> TdarrAdmin(state, viewModel)
            }
        }
        Spacer(Modifier.height(36.dp))
    }

    state.pendingConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(confirmation.title) },
            text = { Text(confirmation.description) },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation) { Text("Cancel") }
            },
            confirmButton = {
                Row {
                    if (confirmation.kind == AutomationConfirmationKind.REMOVE_QUEUE_ITEM) {
                        TextButton(onClick = { viewModel.confirmPendingAction(blocklistAndSearchAgain = true) }) {
                            Text("Blocklist & search", color = Amber)
                        }
                    }
                    TextButton(onClick = { viewModel.confirmPendingAction() }) {
                        Text("Confirm", color = Ruby)
                    }
                }
            },
        )
    }
}

@Composable
private fun LibraryAdmin(
    state: AutomationAdministrationUiState,
    viewModel: AutomationAdministrationViewModel,
    profileIndex: Int,
    rootIndex: Int,
    onNextProfile: () -> Unit,
    onNextRoot: () -> Unit,
) {
    AdminCard {
        Text("Add a title", color = Snow, fontWeight = FontWeight.SemiBold)
        Text("Lookup does not add anything until you select Add.", color = Silver, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Movie or series") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = viewModel::lookupMedia, enabled = state.busyAction == null, colors = adminButtonColors()) {
            Text("Search")
        }
        if (state.lookupResults.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onNextProfile, modifier = Modifier.fillMaxWidth()) {
                Text("Quality: ${state.qualityProfiles.getOrNull(profileIndex)?.name ?: "Not available"}", color = Snow)
            }
            OutlinedButton(onClick = onNextRoot, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Folder: ${state.rootFolders.getOrNull(rootIndex)?.path ?: "Not available"}",
                    color = Snow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    state.lookupResults.forEach { item ->
        MediaAdminRow(item, "Add") {
            viewModel.addMedia(
                item,
                state.qualityProfiles.getOrNull(profileIndex)?.id ?: 0,
                state.rootFolders.getOrNull(rootIndex)?.path.orEmpty(),
            )
        }
    }
    if (state.library.isNotEmpty()) {
        Text("Managed library (${state.library.size})", color = Snow, fontWeight = FontWeight.SemiBold)
        state.library.forEach { item ->
            MediaAdminRow(item, "Find releases") { viewModel.searchReleases(item.id) }
        }
    }
}

@Composable
private fun MediaAdminRow(item: AutomationLibraryItem, action: String, onClick: () -> Unit) {
    AdminCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, color = Snow, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(item.year?.toString(), item.kind.name.lowercase(), if (item.hasFile) "downloaded" else null).joinToString(" · "),
                    color = Silver,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onClick) { Text(action, color = Amber) }
        }
    }
}

@Composable
private fun ReleaseAdmin(releases: List<AutomationRelease>, viewModel: AutomationAdministrationViewModel) {
    EmptyHint(releases.isEmpty(), "Choose Find releases from a managed movie or series.")
    releases.forEach { release ->
        AdminCard {
            Text(release.title, color = Snow, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(release.quality, release.indexer, release.seeders?.let { "$it seeders" }, release.sizeBytes?.let(::formatBytes)).joinToString(" · "),
                color = Silver,
                style = MaterialTheme.typography.bodySmall,
            )
            if (release.rejections.isNotEmpty()) {
                Text(release.rejections.joinToString(" · "), color = Ruby, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = { viewModel.grabRelease(release) }, colors = adminButtonColors()) { Text("Grab release") }
        }
    }
}

@Composable
private fun QueueAdmin(queue: List<AutomationQueueItem>, viewModel: AutomationAdministrationViewModel) {
    EmptyHint(queue.isEmpty(), "The acquisition queue is empty.")
    queue.forEach { item -> QueueRow(item, viewModel) }
}

@Composable
private fun QueueRow(item: AutomationQueueItem, viewModel: AutomationAdministrationViewModel) {
    AdminCard {
        Text(item.title, color = Snow, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(item.status, item.progressPercent?.let { "${it.toInt()}%" }, item.timeLeft, item.errorMessage).joinToString(" · "),
            color = if (item.errorMessage == null) Silver else Ruby,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.retryQueueItem(item) }) { Text("Retry", color = Amber) }
            OutlinedButton(onClick = { viewModel.requestRemoveQueueItem(item) }) { Text("Remove", color = Ruby) }
        }
    }
}

@Composable
private fun IndexerAdmin(indexers: List<AutomationIndexer>, viewModel: AutomationAdministrationViewModel) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiPath by remember { mutableStateOf("/api") }
    var apiKey by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(AutomationIndexerProtocol.TORRENT) }
    AdminCard {
        Text("Add generic indexer", color = Snow, fontWeight = FontWeight.SemiBold)
        Text("Supports Torznab torrent and Newznab usenet endpoints.", color = Silver, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomationIndexerProtocol.entries.forEach { choice ->
                val label = if (choice == AutomationIndexerProtocol.TORRENT) "Torznab" else "Newznab"
                if (protocol == choice) {
                    Button(onClick = { protocol = choice }, colors = adminButtonColors()) { Text(label) }
                } else {
                    OutlinedButton(onClick = { protocol = choice }) { Text(label, color = Snow) }
                }
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
        OutlinedTextField(apiPath, { apiPath = it }, Modifier.fillMaxWidth(), label = { Text("API path") }, singleLine = true)
        OutlinedTextField(
            apiKey,
            { apiKey = it },
            Modifier.fillMaxWidth(),
            label = { Text("Indexer API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Button(
            onClick = {
                viewModel.createIndexer(
                    AutomationIndexerCreateRequest(
                        name = name,
                        protocol = protocol,
                        baseUrl = baseUrl,
                        apiPath = apiPath,
                        apiKey = apiKey,
                    ),
                )
            },
            enabled = name.isNotBlank() && baseUrl.isNotBlank(),
            colors = adminButtonColors(),
        ) { Text("Add and enable") }
    }
    EmptyHint(indexers.isEmpty(), "No indexers are attached to this service.")
    indexers.forEach { indexer ->
        AdminCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(indexer.name, color = Snow, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(indexer.implementation, indexer.protocol, indexer.status).joinToString(" · "), color = Silver, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = indexer.enabled, onCheckedChange = { viewModel.setIndexerEnabled(indexer, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.testIndexer(indexer) }) { Text("Test", color = Amber) }
                OutlinedButton(onClick = { viewModel.requestDeleteIndexer(indexer) }) { Text("Delete", color = Ruby) }
            }
        }
    }
}

@Composable
private fun SubtitleAdmin(state: AutomationAdministrationUiState, viewModel: AutomationAdministrationViewModel) {
    EmptyHint(state.wantedSubtitles.isEmpty(), "Bazarr has no wanted subtitles right now.")
    state.wantedSubtitles.forEach { target -> SubtitleTargetRow(target, viewModel) }
    val target = state.selectedSubtitleTarget
    if (target != null && state.subtitleCandidates.isNotEmpty()) {
        Text("Candidates for ${target.title}", color = Snow, fontWeight = FontWeight.SemiBold)
        state.subtitleCandidates.forEach { candidate -> SubtitleCandidateRow(target, candidate, viewModel) }
    }
}

@Composable
private fun SubtitleTargetRow(target: AutomationSubtitleTarget, viewModel: AutomationAdministrationViewModel) {
    AdminCard {
        Text(target.title, color = Snow, fontWeight = FontWeight.SemiBold)
        Text("Missing: ${target.missingLanguages.joinToString().ifBlank { "configured languages" }}", color = Silver, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.searchSubtitles(target) }, colors = adminButtonColors()) { Text("Interactive search") }
            OutlinedButton(onClick = { viewModel.searchMissingSubtitle(target, target.missingLanguages.firstOrNull().orEmpty()) }) {
                Text("Auto search", color = Amber)
            }
        }
    }
}

@Composable
private fun SubtitleCandidateRow(
    target: AutomationSubtitleTarget,
    candidate: AutomationSubtitleCandidate,
    viewModel: AutomationAdministrationViewModel,
) {
    AdminCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${candidate.language} · ${candidate.provider}", color = Snow, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(candidate.score?.let { "Score ${it.toInt()}" }, candidate.release).joinToString(" · "), color = Silver, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Button(onClick = { viewModel.downloadSubtitle(target, candidate) }, colors = adminButtonColors()) { Text("Download") }
        }
    }
}

@Composable
private fun TdarrAdmin(state: AutomationAdministrationUiState, viewModel: AutomationAdministrationViewModel) {
    val tdarr = state.tdarr
    if (tdarr == null) {
        EmptyHint(true, "Connect to Tdarr and refresh to load libraries, nodes and jobs.")
        return
    }
    Text("Libraries", color = Snow, fontWeight = FontWeight.SemiBold)
    tdarr.libraries.forEach { library ->
        AdminCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(library.name, color = Snow, fontWeight = FontWeight.SemiBold)
                    Text(library.sourcePath.orEmpty(), color = Silver, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { viewModel.scanTdarrLibrary(TdarrScanRequest(library.id)) }, colors = adminButtonColors()) { Text("Scan") }
            }
        }
    }
    Text("Automations", color = Snow, fontWeight = FontWeight.SemiBold)
    tdarr.automations.forEach { automation ->
        AdminCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(automation.name, color = Snow, modifier = Modifier.weight(1f))
                Button(
                    onClick = { viewModel.runTdarrAutomation(TdarrRunAutomationRequest(automation.id, executeImmediately = true)) },
                    colors = adminButtonColors(),
                ) { Text("Run") }
            }
        }
    }
    Text("Nodes and active workers", color = Snow, fontWeight = FontWeight.SemiBold)
    tdarr.nodes.forEach { node ->
        AdminCard {
            Text("${node.name} · ${if (node.online) "online" else "offline"}", color = if (node.online) Emerald else Ruby, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.changeTdarrWorkerLimit(TdarrWorkerLimitRequest(node.id, "transcodecpu", TdarrWorkerLimitChange.DECREASE))
                }) { Text("CPU −", color = Snow) }
                OutlinedButton(onClick = {
                    viewModel.changeTdarrWorkerLimit(TdarrWorkerLimitRequest(node.id, "transcodecpu", TdarrWorkerLimitChange.INCREASE))
                }) { Text("CPU +", color = Amber) }
            }
            node.workers.forEach { worker ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("${worker.workerType} · ${worker.status}", color = Silver, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.requestCancelTdarrWorker(TdarrWorkerAction(node.id, worker.workerId)) }) {
                        Text("Cancel", color = Ruby)
                    }
                }
            }
        }
    }
    Text("Jobs (${tdarr.jobs.size})", color = Snow, fontWeight = FontWeight.SemiBold)
    tdarr.jobs.take(100).forEach { job ->
        AdminCard {
            Text(job.file.substringAfterLast('/').substringAfterLast('\\'), color = Snow, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(job.status, job.progressPercent?.let { "${it.toInt()}%" }).joinToString(" · "), color = Silver, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tdarrJobActions(job).forEach { action ->
                    OutlinedButton(onClick = { viewModel.actOnTdarrJob(TdarrJobActionRequest(job.id, action)) }) {
                        Text(action.label(), color = if (action == TdarrJobAction.SKIP) Ruby else Snow)
                    }
                }
            }
        }
    }
}

private fun tdarrJobActions(job: TdarrJob): List<TdarrJobAction> = when (job.status) {
    "transcodeSuccess", "conditionsMet" -> listOf(TdarrJobAction.ACCEPT, TdarrJobAction.REQUEUE, TdarrJobAction.SKIP)
    "copyFailed", "transcodeError", "transcodeCancelled" -> listOf(TdarrJobAction.RETRY, TdarrJobAction.ACCEPT, TdarrJobAction.REQUEUE, TdarrJobAction.SKIP)
    "requireReview" -> listOf(TdarrJobAction.REVIEWED, TdarrJobAction.SKIP)
    else -> emptyList()
}

private fun TdarrJobAction.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun AdminCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Gunmetal.copy(alpha = 0.86f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), content = content)
    }
}

@Composable
private fun AdminStatus(message: String, color: androidx.compose.ui.graphics.Color, showSpinner: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(message, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyHint(show: Boolean, text: String) {
    if (show) AdminCard { Text(text, color = Silver) }
}

@Composable
private fun adminButtonColors() = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian)

private fun supportedSections(state: AutomationAdministrationUiState): List<AutomationAdminSection> = buildList {
    if (AutomationCapability.LIBRARY_LOOKUP in state.capabilities) add(AutomationAdminSection.LIBRARY)
    if (AutomationCapability.RELEASE_SEARCH in state.capabilities) add(AutomationAdminSection.RELEASES)
    if (AutomationCapability.QUEUE_READ in state.capabilities) add(AutomationAdminSection.QUEUE)
    if (AutomationCapability.INDEXER_READ in state.capabilities) add(AutomationAdminSection.INDEXERS)
    if (AutomationCapability.SUBTITLE_WANTED in state.capabilities) add(AutomationAdminSection.SUBTITLES)
    if (AutomationCapability.TDARR_LIBRARIES in state.capabilities) add(AutomationAdminSection.TDARR)
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) "${(gib * 10).toInt() / 10.0} GB" else "${bytes / (1024 * 1024)} MB"
}
