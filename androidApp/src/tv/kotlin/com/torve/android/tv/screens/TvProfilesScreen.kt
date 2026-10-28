package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.tv.components.*
import com.torve.android.ui.player.ActivePlaybackState
import com.torve.android.ui.theme.*
import com.torve.data.profile.HouseholdSyncService
import com.torve.domain.model.*
import com.torve.presentation.profile.ProfileViewModel
import org.koin.compose.koinInject

@Composable
fun TvProfilesScreen(onBack: () -> Unit, onSwitched: () -> Unit, onContentFocused: (FocusRequester) -> Unit,
    viewModel: ProfileViewModel = koinInject(), household: HouseholdSyncService = koinInject()) {
    TvWorkflowSurface("profiles") { TvProfilesContent(onBack, onSwitched, onContentFocused, viewModel, household) }
}

@Composable
private fun TvProfilesContent(onBack: () -> Unit, onSwitched: () -> Unit, onContentFocused: (FocusRequester) -> Unit,
    viewModel: ProfileViewModel, household: HouseholdSyncService) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val sync by household.state.collectAsState()
    val entry = remember { FocusRequester() }
    val create = remember { FocusRequester() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UserProfile?>(null) }
    var deleting by remember { mutableStateOf<UserProfile?>(null) }
    var origin by remember { mutableStateOf<FocusRequester?>(null) }
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    var pendingManagement by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var playbackMessage by remember { mutableStateOf(false) }
    LaunchedEffect(state.activeProfile?.id, pendingSwitch) {
        if (pendingSwitch != null && state.activeProfile?.id == pendingSwitch && state.pinPromptProfileId == null) {
            pendingSwitch = null; onSwitched()
        }
    }
    LaunchedEffect(sync.lastSyncedAt) { viewModel.loadProfiles() }
    LaunchedEffect(state.editingProfile?.id) {
        state.editingProfile?.let { allowed ->
            pendingManagement?.takeIf { it.first == allowed.id }?.let {
                if (it.second) deleting = allowed else editing = allowed
                pendingManagement = null
            }
        }
    }
    LaunchedEffect(state.savedProfileRevision) { if (state.savedProfileRevision > 0) editing = null }
    TvWorkflowPage(stringResource(R.string.flow_who_watching), if (playbackMessage) stringResource(R.string.flow_profile_stop_playback)
        else state.error ?: sync.error ?: stringResource(R.string.flow_profiles_sub),
        onBack, entry, onContentFocused, toolbar = {
            TvWorkflowAction(stringResource(R.string.flow_profile_create), { creating = true; origin = create }, requester = create)
            TvWorkflowAction(stringResource(R.string.flow_profile_sync), { household.requestRefresh(force = true); viewModel.loadProfiles() })
        }) {
        items(state.profiles, key = { it.id }) { profile ->
            val requester = remember(profile.id) { FocusRequester() }
            val editRequester = remember(profile.id) { FocusRequester() }
            val deleteRequester = remember(profile.id) { FocusRequester() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvWorkflowAction("${if (profile.isActive) "✓  " else ""}${profile.name}", {
                    origin = requester
                    if (ActivePlaybackState.session != null) playbackMessage = true
                    else if (!profile.isActive) { pendingSwitch = profile.id; viewModel.switchProfile(profile.id) }
                }, Modifier.weight(1f), subtitle = buildString {
                    append(if (profile.isActive) context.getString(R.string.flow_profile_active) else context.getString(R.string.flow_profile_switch))
                    if (!profile.pin.isNullOrBlank()) append(" · " + context.getString(R.string.flow_profile_pin_protected))
                    profile.maxContentRating?.let { append(" · " + context.getString(R.string.flow_profile_rating_up_to, it.label)) }
                }, selected = profile.isActive, requester = requester, onFocused = { onContentFocused(requester) })
                TvWorkflowAction(stringResource(R.string.flow_edit), { origin = editRequester; pendingManagement = profile.id to false;
                    viewModel.requestProfileManagement(profile.id) }, requester = editRequester)
                if (profile.id != "default" && !profile.isActive) TvWorkflowAction(stringResource(R.string.common_delete), { origin = deleteRequester; pendingManagement = profile.id to true;
                    viewModel.requestProfileManagement(profile.id) }, destructive = true, requester = deleteRequester)
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        val initialSize = remember { state.profiles.size }
        LaunchedEffect(state.profiles.size) { if (state.profiles.size > initialSize) creating = false }
        TvWorkflowDialog(stringResource(R.string.flow_profile_create), { creating = false; viewModel.clearError() }, origin) {
            TvClickToEditOutlinedTextField(name, { name = it }, { Text(stringResource(R.string.flow_name)) }, true, Modifier.fillMaxWidth())
            state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            TvWorkflowAction(stringResource(R.string.flow_create), { viewModel.createProfile(name) })
        }
    }
    editing?.let { profile ->
        var name by remember(profile.id) { mutableStateOf(profile.name) }
        var pin by remember(profile.id) { mutableStateOf("") }
        var pinEdited by remember { mutableStateOf(false) }
        var rating by remember(profile.id) { mutableStateOf(profile.maxContentRating) }
        TvWorkflowDialog(stringResource(R.string.flow_manage_named, profile.name), { if (!state.isSavingProfile) {
            editing = null; viewModel.dismissEditDialog(); viewModel.clearError()
        } }, origin) {
            TvClickToEditOutlinedTextField(name, { name = it }, { Text(stringResource(R.string.flow_name)) }, true, Modifier.fillMaxWidth())
            TvClickToEditOutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4); pinEdited = true },
                { Text(if (profile.pin.isNullOrBlank()) stringResource(R.string.flow_pin_optional) else stringResource(R.string.flow_pin_unchanged)) }, true,
                Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvWorkflowAction(stringResource(R.string.flow_rating_named, rating?.label ?: stringResource(R.string.flow_all_ratings)), {
                    val choices = listOf<ContentRating?>(null) + ContentRating.entries
                    rating = choices[(choices.indexOf(rating) + 1) % choices.size]
                })
                if (!profile.pin.isNullOrBlank()) TvWorkflowAction(stringResource(R.string.flow_pin_remove), { pin = ""; pinEdited = true })
            }
            state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            TvWorkflowAction(stringResource(R.string.flow_save_changes), {
                viewModel.saveProfile(profile.id, name, rating, pin, pinEdited)
            }, subtitle = if (state.isSavingProfile) stringResource(R.string.flow_saving) else null)
        }
    }
    state.pinPromptProfileId?.let { id ->
        var pin by remember(id) { mutableStateOf("") }
        TvWorkflowDialog(stringResource(R.string.flow_profile_enter_pin), { viewModel.dismissPinPrompt(); pendingSwitch = null; pendingManagement = null }, origin) {
            TvClickToEditOutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, { Text(stringResource(R.string.flow_pin_four)) }, true,
                Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            state.pinError?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            TvWorkflowAction(stringResource(R.string.flow_continue), { viewModel.verifyPinAndSwitch(id, pin) })
        }
    }
    deleting?.let { profile ->
        TvWorkflowDialog(stringResource(R.string.flow_delete_named, profile.name), { deleting = null; viewModel.dismissEditDialog() }, origin) {
            Text(stringResource(R.string.flow_profile_delete_sub), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvWorkflowAction(stringResource(R.string.flow_profile_delete), {
                viewModel.deleteProfile(profile.id); deleting = null; origin = create
            }, destructive = true)
        }
    }
}
