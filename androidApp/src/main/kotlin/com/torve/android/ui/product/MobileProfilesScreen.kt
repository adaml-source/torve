package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel as localizedUiText

import com.torve.android.ui.community.parityLabel
import com.torve.android.ui.system.TorveModalBottomSheet as ModalBottomSheet
import com.torve.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.domain.model.*
import com.torve.presentation.profile.ProfileViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileProfilesScreen(onBack: () -> Unit, viewModel: ProfileViewModel = koinInject()) {
    val state by viewModel.state.collectAsState()
    var create by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<UserProfile?>(null) }
    LaunchedEffect(viewModel) { viewModel.loadProfiles() }
    ProductPage(stringResource(R.string.flow_profiles), state.activeProfile?.let { stringResource(R.string.flow_profile_using, it.name) }, onBack, actions = {
        IconButton(onClick = { viewModel.clearError(); create = true }, enabled = !state.isSavingProfile && state.profiles.size < ProfileViewModel.MAX_PROFILES) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = stringResource(R.string.flow_profile_create))
        }
    }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("intro") { Text(stringResource(R.string.flow_profiles_sub),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            state.error?.let { item("error") { ProductFeedback(it, true) } }
            items(state.profiles, key = { it.id }) { profile ->
                val active = profile.id == state.activeProfile?.id
                Column {
                    ProductToolRow(profile.name, buildList {
                        if (active) add(stringResource(R.string.flow_profile_active))
                        add(profile.maxContentRating?.let { stringResource(R.string.flow_profile_rating_up_to, it.label) } ?: stringResource(R.string.flow_profile_ratings_all))
                        if (!profile.pin.isNullOrBlank()) add(stringResource(R.string.flow_profile_pin_protected))
                    }.joinToString(" · "), profileAvatarIcons.getOrElse(profile.avatarIndex) { profileAvatarIcons.first() },
                        onClick = { if (!active) viewModel.switchProfile(profile.id) }, selected = active, enabled = !state.isSavingProfile)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.clearError(); viewModel.showEditDialog(profile) }, enabled = !state.isSavingProfile) { Text(stringResource(R.string.flow_profile_manage)) }
                    }
                }
            }
            if (state.profiles.size >= ProfileViewModel.MAX_PROFILES) item("limit") { ProductFeedback(stringResource(R.string.flow_profile_limit, ProfileViewModel.MAX_PROFILES)) }
        }
    }
    if (create) {
        var name by remember { mutableStateOf("") }
        var avatar by remember { mutableIntStateOf(0) }
        val initialCount = remember { state.profiles.size }
        LaunchedEffect(state.profiles.size) { if (state.profiles.size > initialCount) create = false }
        AlertDialog(onDismissRequest = { if (!state.isSavingProfile) create = false }, title = { Text(stringResource(R.string.flow_profile_create)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(ProfileViewModel.MAX_PROFILE_NAME_LENGTH) }, label = { Text(stringResource(R.string.flow_profile_name)) }, singleLine = true, enabled = !state.isSavingProfile)
                ProfileAvatarPicker(avatar) { avatar = it }
                state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = { viewModel.createProfile(name.trim(), avatar) }, enabled = name.isNotBlank() && !state.isSavingProfile) {
                Text(if (state.isSavingProfile) stringResource(R.string.flow_creating) else stringResource(R.string.flow_create))
            } }, dismissButton = { TextButton(onClick = { create = false }, enabled = !state.isSavingProfile) { Text(stringResource(R.string.common_cancel)) } })
    }
    state.pinPromptProfileId?.let { id ->
        var pin by remember(id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = viewModel::dismissPinPrompt,
            title = { Text(stringResource(R.string.flow_profile_enter_pin)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.profiles.firstOrNull { it.id == id }?.name.orEmpty())
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.flow_pin_four)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), isError = state.pinError != null)
                state.pinError?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = { viewModel.verifyPinAndSwitch(id, pin); pin = "" }, enabled = pin.length == 4) { Text(stringResource(R.string.flow_continue)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissPinPrompt) { Text(stringResource(R.string.common_cancel)) } })
    }
    state.editingProfile?.let { profile ->
        var name by remember(profile.id) { mutableStateOf(profile.name) }
        var rating by remember(profile.id) { mutableStateOf(profile.maxContentRating) }
        var changePin by remember(profile.id) { mutableStateOf(false) }
        var newPin by remember(profile.id) { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { if (!state.isSavingProfile) viewModel.dismissEditDialog() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text(stringResource(R.string.flow_manage_named, profile.name), style = MaterialTheme.typography.headlineSmall) }
                item { OutlinedTextField(name, { name = it.take(ProfileViewModel.MAX_PROFILE_NAME_LENGTH) }, label = { Text(stringResource(R.string.flow_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.isSavingProfile) }
                item { Text(stringResource(R.string.flow_rating_limit), style = MaterialTheme.typography.titleMedium) }
                item {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(rating == null, { rating = null }, label = { Text(stringResource(R.string.flow_all_ratings)) }, enabled = !state.isSavingProfile)
                        ContentRating.entries.forEach { value -> FilterChip(rating == value, { rating = value }, label = { Text(value.label) }, enabled = !state.isSavingProfile) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (profile.pin.isNullOrBlank()) stringResource(R.string.flow_pin_set) else stringResource(R.string.flow_pin_change_remove))
                        Switch(changePin, { changePin = it }, enabled = !state.isSavingProfile)
                    }
                }
                if (changePin) item { OutlinedTextField(newPin, { newPin = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.flow_pin_new)) }, supportingText = { Text(stringResource(R.string.flow_pin_remove_sub)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(), enabled = !state.isSavingProfile, singleLine = true) }
                state.error?.let { item { ProductFeedback(it, true) } }
                item { Button(onClick = { viewModel.saveProfile(profile.id, name.trim(), rating, newPin, changePin) },
                    enabled = name.isNotBlank() && !state.isSavingProfile && (!changePin || newPin.isEmpty() || newPin.length == 4), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (state.isSavingProfile) stringResource(R.string.flow_saving) else stringResource(R.string.flow_save_changes))
                } }
                if (profile.id != "default" && profile.id != state.activeProfile?.id && state.profiles.size > 1) item {
                    TextButton(onClick = { deleting = profile }, enabled = !state.isSavingProfile, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.flow_profile_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.flow_delete_named, profile.name)) },
        text = { Text(stringResource(R.string.flow_profile_delete_sub)) },
        confirmButton = { TextButton(onClick = { deleting = null; viewModel.deleteProfile(profile.id) }, enabled = !state.isSavingProfile) { Text(stringResource(R.string.flow_profile_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } }) }
}

private val profileAvatarIcons = listOf(Icons.Rounded.Person, Icons.Rounded.Face, Icons.Rounded.Star,
    Icons.Rounded.Favorite, Icons.Rounded.Bolt, Icons.Rounded.LocalFireDepartment, Icons.Rounded.Park, Icons.Rounded.DarkMode)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileAvatarPicker(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Text(parityLabel("Avatar"), style = MaterialTheme.typography.titleSmall)
    val labels = listOf("Person", "Face", "Star", "Heart", "Bolt", "Flame", "Leaf", "Moon")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        profileAvatarIcons.forEachIndexed { index, icon ->
            OutlinedIconButton(onClick = { onSelect(index) }, modifier = Modifier.size(48.dp).semantics { selected = selectedIndex == index },
                colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = if (selectedIndex == index) MaterialTheme.colorScheme.primary.copy(alpha = .2f) else androidx.compose.ui.graphics.Color.Transparent)) {
                Icon(icon, labels[index], tint = if (selectedIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
