package com.torve.desktop.ui.v2.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.torve.data.profile.HouseholdSyncService
import com.torve.data.simkl.SimklSyncService
import com.torve.desktop.ui.components.*
import com.torve.desktop.ui.l10n.ds
import com.torve.domain.repository.ProfileRepository
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DesktopSyncStatus(settings: SettingsUiState, vm: SettingsViewModel) {
    val household = remember { KoinPlatform.getKoin().get<HouseholdSyncService>() }
    val simkl = remember { KoinPlatform.getKoin().get<SimklSyncService>() }
    val torveState by household.state.collectAsState()
    val simklState by simkl.state.collectAsState()
    val scope = rememberCoroutineScope()
    var profileName by remember(settings.personalDataUserId) { mutableStateOf("") }
    LaunchedEffect(settings.personalDataUserId) {
        profileName = KoinPlatform.getKoin().get<ProfileRepository>().getActiveProfile()?.name.orEmpty()
    }
    @Composable fun status(time: Long?, busy: Boolean, error: String?): String = when {
        busy -> "Syncing..."
        error != null -> error
        time != null -> "${ds("Last sync")}: ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))}"
        else -> ds("Not synced yet")
    }
    TorveSectionCard(title = ds("Sync status"), supportingText = "${ds("Active profile")}: $profileName") {
        val sameTorve = torveState.personalDataUserId == settings.personalDataUserId
        TorveListRow("Torve", status(torveState.lastSyncedAt.takeIf { sameTorve }, torveState.syncing && sameTorve, torveState.error.takeIf { sameTorve }),
            trailing = { TorveGhostButton(ds("Retry"), { scope.launch { household.refresh(force = true) } }, enabled = !torveState.syncing) })
        TorveListRow("Trakt", if (settings.traktConnected) status(settings.traktLastSyncTime, settings.traktSyncing, settings.traktError) else ds("Disconnected"),
            trailing = { TorveGhostButton(ds("Retry"), vm::syncTraktNow, enabled = settings.traktConnected && !settings.traktSyncing) })
        val sameSimkl = simklState.personalDataUserId == settings.personalDataUserId
        TorveListRow("SIMKL", if (settings.simklConnected) status(simklState.lastSyncedAt.takeIf { sameSimkl }, simklState.running && sameSimkl, simklState.error.takeIf { sameSimkl }) else ds("Disconnected"),
            trailing = { TorveGhostButton(ds("Retry"), vm::checkSimklConnection, enabled = settings.simklConnected && !simklState.running && !settings.simklLoading) })
    }
}
