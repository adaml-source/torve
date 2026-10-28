package com.torve.android.ui.sync

import com.torve.android.ui.community.parityLabel as localizedUiText

import com.torve.android.ui.system.TorveModalBottomSheet as ModalBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.torve.android.R
import com.torve.data.auth.UserIdProvider
import com.torve.data.auth.withPersonalDataScope
import com.torve.domain.repository.PreferencesRepository
import androidx.compose.ui.unit.dp
import com.torve.android.sync.SyncCoordinator
import com.torve.android.sync.model.*
import com.torve.android.ui.product.*
import com.torve.data.kodi.*
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.koin.compose.koinInject

internal data class RemoteTarget(val id: String, val name: String, val tvId: String? = null, val kodi: KodiHost? = null)

@Composable
fun CompanionRemoteScreen(onBack: () -> Unit, onDevices: () -> Unit = {}, sync: SyncCoordinator = koinInject(),
    settings: SettingsViewModel = koinInject(), kodi: KodiClient = koinInject(),
    ids: UserIdProvider = koinInject(), prefs: PreferencesRepository = koinInject()) {
    val state by sync.state.collectAsState()
    val config by settings.state.collectAsState()
    val targets = state.devices.filter { it.revokedAt == null && it.deviceType.contains("tv", true) }
        .map { RemoteTarget("tv:${it.id}", it.deviceName, tvId = it.id) } +
        config.kodiHosts.map { RemoteTarget("kodi:${it.ip}:${it.port}", "Kodi · ${it.name}", kodi = it) }
    var selectedId by rememberSaveable(state.userId, config.personalDataUserId) { mutableStateOf<String?>(null) }
    var error by remember(state.userId, config.personalDataUserId) { mutableStateOf<String?>(null) }
    val owner = config.personalDataUserId
    val context = LocalContext.current
    var busy by remember(owner) { mutableStateOf(false) }
    var operation by remember(owner) { mutableStateOf<Job?>(null) }
    var seekSeconds by rememberSaveable { mutableIntStateOf(10) }
    val selected = targets.firstOrNull { it.id == selectedId }
    val ready = selected?.kodi != null || selected?.tvId?.let { state.secureChannelStates[it] == SecureChannelState.PAIRED_SECURE_CHANNEL_READY } == true
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(sync) { sync.refreshDevices() }
    LaunchedEffect(owner) {
        val previous = ids.withPersonalDataScope { prefs.getString("companion_last_target") }
        if (owner == ids.currentDataUserId()) selectedId = previous
    }
    DisposableEffect(owner) { onDispose { operation?.cancel() } }
    fun send(command: TorveRemoteCommand) {
        val chosen = selected ?: return
        if (busy || !ready) return
        busy = true
        error = null
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        operation = scope.launch { ids.withPersonalDataScope {
            try {
                val result = chosen.kodi?.let { host -> runCatching { kodi.control(host, KodiRemoteAction.valueOf(
                    if (command == TorveRemoteCommand.MENU) "CONTEXT_MENU" else command.name)) } }
                    ?: sync.sendRemoteCommand(chosen.tvId!!, command, seekSeconds * 1_000L)
                if (result.isFailure && selectedId == chosen.id) error = context.getString(R.string.gap_remote_failed)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (selectedId == chosen.id) error = context.getString(R.string.gap_remote_failed)
            } finally { busy = false }
        } }
    }
    MobileRemoteContent(targets, selectedId, ready, busy, seekSeconds, error, onBack,
        onSelect = { target -> if (!busy && targets.any { it.id == target }) {
            selectedId = target; error = null
            scope.launch { ids.withPersonalDataScope { prefs.setString("companion_last_target", target) } }
        } }, onCommand = ::send,
        onConnect = {
            val chosen = selected
            if (!busy && chosen?.tvId != null) {
                busy = true
                error = null
                operation = scope.launch { ids.withPersonalDataScope {
                    try {
                        val result = sync.bootstrapSecureChannel(chosen.tvId)
                        if (result.isFailure && selectedId == chosen.id) error = context.getString(R.string.gap_remote_connect_failed)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (selectedId == chosen.id) error = context.getString(R.string.gap_remote_connect_failed)
                    } finally { busy = false }
                } }
            }
        }, onSeekStep = { seekSeconds = if (seekSeconds == 10) 30 else 10 }, onDevices = onDevices)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileRemoteContent(targets: List<RemoteTarget>, selectedId: String?, ready: Boolean, busy: Boolean,
    seekSeconds: Int, error: String?, onBack: () -> Unit, onSelect: (String) -> Unit, onCommand: (TorveRemoteCommand) -> Unit,
    onConnect: () -> Unit, onSeekStep: () -> Unit, onDevices: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val selected = targets.firstOrNull { it.id == selectedId }
    val enabled = selected != null && ready && !busy
    ProductPage(stringResource(R.string.gap_remote_title), stringResource(R.string.gap_remote_detail), onBack) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            item("target") {
                ProductToolRow(selected?.name ?: stringResource(R.string.gap_remote_choose), when {
                    busy -> stringResource(R.string.gap_remote_sending)
                    selected == null -> stringResource(R.string.gap_remote_select)
                    selected.kodi != null -> stringResource(R.string.gap_remote_kodi)
                    ready -> stringResource(R.string.gap_remote_ready)
                    else -> stringResource(R.string.gap_remote_open)
                }, Icons.Rounded.Tv, { choosing = true }, enabled = !busy, modifier = Modifier.testTag("remote_target"))
            }
            error?.let { item("error") { ProductFeedback(it, true) } }
            if (selected?.tvId != null && !ready) item("connect") {
                Button(onClick = onConnect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) stringResource(R.string.gap_remote_sending) else stringResource(R.string.gap_remote_connect)) }
            }
            if (targets.isEmpty()) item("empty") {
                ProductEmptyState(stringResource(R.string.gap_remote_empty), stringResource(R.string.gap_remote_empty_detail)) {
                    FilledTonalButton(onClick = onDevices) { Text(stringResource(R.string.gap_remote_devices)) }
                }
            }
            item("dpad") {
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteDirection(stringResource(R.string.gap_remote_up), Icons.Rounded.KeyboardArrowUp, TorveRemoteCommand.UP, enabled, onCommand)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteDirection(stringResource(R.string.gap_remote_left), Icons.Rounded.KeyboardArrowLeft, TorveRemoteCommand.LEFT, enabled, onCommand)
                        FilledTonalButton(onClick = { onCommand(TorveRemoteCommand.SELECT) }, enabled = enabled,
                            modifier = Modifier.size(76.dp).testTag("remote_SELECT"), shape = CircleShape) { Text(localizedUiText("OK")) }
                        RemoteDirection(stringResource(R.string.gap_remote_right), Icons.Rounded.KeyboardArrowRight, TorveRemoteCommand.RIGHT, enabled, onCommand)
                    }
                    RemoteDirection(stringResource(R.string.gap_remote_down), Icons.Rounded.KeyboardArrowDown, TorveRemoteCommand.DOWN, enabled, onCommand)
                }
            }
            item("navigation") {
                Row(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteAction(stringResource(R.string.gap_remote_back), Icons.AutoMirrored.Rounded.ArrowBack, TorveRemoteCommand.BACK, enabled, onCommand, Modifier.weight(1f))
                    RemoteAction(stringResource(R.string.gap_remote_home), Icons.Rounded.Home, TorveRemoteCommand.HOME, enabled, onCommand, Modifier.weight(1f))
                    RemoteAction(stringResource(R.string.gap_remote_menu), Icons.Rounded.MoreHoriz, TorveRemoteCommand.MENU, enabled, onCommand, Modifier.weight(1f))
                }
            }
            item("playback") {
                Row(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteAction(if (selected?.kodi != null) stringResource(R.string.gap_remote_rewind) else "−${seekSeconds}s", Icons.Rounded.FastRewind, TorveRemoteCommand.SEEK_BACK, enabled, onCommand, Modifier.weight(1f))
                    RemoteAction(stringResource(R.string.gap_remote_play), Icons.Rounded.PlayArrow, TorveRemoteCommand.PLAY_PAUSE, enabled, onCommand, Modifier.weight(1.25f))
                    RemoteAction(if (selected?.kodi != null) stringResource(R.string.gap_remote_forward) else "+${seekSeconds}s", Icons.Rounded.FastForward, TorveRemoteCommand.SEEK_FORWARD, enabled, onCommand, Modifier.weight(1f))
                }
            }
            item("stop") {
                OutlinedButton(onClick = { onCommand(TorveRemoteCommand.STOP) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("remote_STOP")) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.gap_remote_stop)) }
            }
            if (selected?.tvId != null) item("seek") { TextButton(onClick = onSeekStep, enabled = !busy) { Text(stringResource(R.string.gap_remote_seek, seekSeconds)) } }
        }
    }
    if (choosing) ModalBottomSheet(onDismissRequest = { choosing = false }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.gap_remote_choose), style = MaterialTheme.typography.headlineSmall) }
            items(targets, key = { it.id }) { target ->
                ProductToolRow(target.name, localizedUiText(if (target.kodi != null) "Kodi" else stringResource(R.string.gap_remote_paired)), Icons.Rounded.Tv,
                    { onSelect(target.id); choosing = false }, selected = target.id == selectedId)
            }
            item { TextButton(onClick = { choosing = false; onDevices() }) { Text(stringResource(R.string.gap_remote_manage)) } }
        }
    }
}

@Composable
private fun RemoteDirection(label: String, icon: ImageVector, command: TorveRemoteCommand, enabled: Boolean, send: (TorveRemoteCommand) -> Unit) {
    FilledIconButton(onClick = { send(command) }, enabled = enabled, modifier = Modifier.size(64.dp).testTag("remote_${command.name}")) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun RemoteAction(label: String, icon: ImageVector, command: TorveRemoteCommand, enabled: Boolean,
    send: (TorveRemoteCommand) -> Unit, modifier: Modifier) {
    Surface(onClick = { send(command) }, enabled = enabled, modifier = modifier.testTag("remote_${command.name}"), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) .8f else .35f)) {
        Column(Modifier.heightIn(min = 64.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(localizedUiText(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
