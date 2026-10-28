package com.torve.android.ui.profile

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.torve.presentation.profile.FamilyControlsViewModel
import org.koin.compose.koinInject
import kotlinx.coroutines.delay

@Composable
fun FamilyAppGate(content: @Composable () -> Unit) {
    val vm: FamilyControlsViewModel = koinInject()
    val state by vm.state.collectAsState()
    val contentKey = vm.controls.contentSessionKey()
    var previousContentKey by remember { mutableStateOf(contentKey) }
    LaunchedEffect(state.access.needsUnlock) {
        if (state.access.needsUnlock) com.torve.android.ui.player.ActivePlaybackState.stopAndClear()
    }
    LaunchedEffect(contentKey) {
        if (previousContentKey != contentKey) com.torve.android.ui.player.ActivePlaybackState.stopAndClear()
        previousContentKey = contentKey
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.controls.background()
            if (event == Lifecycle.Event.ON_START) { vm.controls.foreground(); vm.refresh() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm) { while (true) { vm.controls.updateSession(); delay(15_000) } }
    if (!state.access.loaded) { Box(Modifier.fillMaxSize()) { CircularProgressIndicator() }; return }
    if (state.access.needsUnlock && !(state.access.parentAuthorized && vm.controls.needsAccountSignIn())) {
        FamilyControlsScreen(lockedProfilePicker = true, vm = vm)
    } else key(vm.controls.contentSessionKey()) { content() }
}

@Composable
fun FamilySettingsLocked(onBack: () -> Unit = {}): Boolean {
    val vm: FamilyControlsViewModel = koinInject()
    val state by vm.state.collectAsState()
    if (state.access.configuration.enabled && !state.access.parentAuthorized) {
        FamilyControlsScreen(onBack = onBack, vm = vm)
        return true
    }
    return false
}

@Composable
fun FamilyControlsScreen(onBack: () -> Unit = {}, lockedProfilePicker: Boolean = false, vm: FamilyControlsViewModel = koinInject()) {
    val state by vm.state.collectAsState()
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var confirmDisable by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var ownerEmail by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    LaunchedEffect(vm) { vm.refresh() }
    @Composable fun action(label: String, enabled: Boolean = true, click: () -> Unit) {
        OutlinedButton(onClick = click, enabled = enabled && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
    }
    @Composable fun pinField(label: String, value: String, change: (String) -> Unit) {
        OutlinedTextField(value, { change(it.filter(Char::isDigit).take(6)) }, label = { Text(label) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(localizedUiText(if (lockedProfilePicker) "Who's watching?" else "Profiles & Family"), style = MaterialTheme.typography.headlineMedium)
        if (!lockedProfilePicker) com.torve.android.ui.components.BackButton(onClick = onBack)
        Text(localizedUiText("Choose what each profile can watch. Age ratings are checked with TMDB. Rules sync to your Torve account across devices. Adult unlocks stay on this device."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.copyingConnections -> {
                Text(localizedUiText("Copy connections"), style = MaterialTheme.typography.titleLarge)
                Text(localizedUiText("Torve history, watchlist and favorites stay separate. Sharing Trakt shares that account's Trakt activity."))
                Text(localizedUiText("From profile"))
                Row(Modifier.horizontalScrollState()) { state.profiles.forEach { profile -> FilterChip(state.copySourceId == profile.id, { vm.setCopySource(profile.id) }, { Text(profile.name) }) } }
                Text(localizedUiText("To profile"))
                Row(Modifier.horizontalScrollState()) { state.profiles.filter { it.id != state.copySourceId }.forEach { profile -> FilterChip(state.copyTargetId == profile.id, { vm.setCopyTarget(profile.id) }, { Text(profile.name) }) } }
                if (state.copyServices.isEmpty()) Text(localizedUiText("No connections found for this profile."))
                state.copyServices.forEach { service -> Row { Checkbox(service in state.selectedCopyServices, { vm.toggleCopyService(service) }); Text(vm.connectionName(service)) } }
                Row { Checkbox(state.copySync, vm::setCopySync); Text(localizedUiText("Sync copied connections through my account")) }
                Text(localizedUiText("Selected credentials are encrypted on Torve's backend and restored on your other devices. Turn this off to copy only here."))
                Row { Checkbox(state.copyReplace, vm::setCopyReplace); Text(localizedUiText("Replace existing destination connections")) }
                action(localizedUiText("Copy selected connections"), state.selectedCopyServices.isNotEmpty() && state.copyTargetId != null && state.copyTargetId != state.copySourceId) { vm.copyConnections() }
                action(localizedUiText("Cancel")) { vm.cancelCopyConnections() }
            }
            !state.access.configuration.enabled -> {
                action(localizedUiText("Copy connections between profiles")) { vm.beginCopyConnections() }
                Text(localizedUiText("Family controls are off"), style = MaterialTheme.typography.titleMedium)
                pinField(localizedUiText("Create parent PIN · 4–6 digits"), pin) { pin = it }
                pinField(localizedUiText("Confirm PIN"), confirmation) { confirmation = it }
                action(localizedUiText("Enable Family controls"), pin.length >= 4 && pin == confirmation) { vm.enable(pin, confirmation); pin = ""; confirmation = "" }
            }
            !state.access.parentAuthorized || lockedProfilePicker -> {
                state.profiles.forEach { profile ->
                    val rules = state.access.configuration.kids[profile.id]
                    action(localizedUiText(profile.name + if (rules != null) " · Kids · up to ${rules.maximumAge}" else " · Adult · Locked")) { vm.selectProfile(profile.id) }
                }
                if (vm.controls.needsAccountSignIn()) {
                    Text(localizedUiText("Sign in to the account that enabled Family controls."))
                    OutlinedTextField(ownerEmail, { ownerEmail = it }, label = { Text(localizedUiText("Account owner email")) })
                    OutlinedTextField(ownerPassword, { ownerPassword = it }, label = { Text(localizedUiText("Account password")) }, visualTransformation = PasswordVisualTransformation())
                    action(localizedUiText("Sign in to Family account"), ownerEmail.isNotEmpty() && ownerPassword.isNotEmpty()) { vm.signInOwner(ownerEmail, ownerPassword); ownerPassword = "" }
                }
                pinField(localizedUiText("Parent PIN"), pin) { pin = it }
                action(localizedUiText("Unlock"), pin.length >= 4) { vm.authorize(pin); pin = "" }
                action(localizedUiText(if (recovery) "Cancel recovery" else "Forgot parent PIN?")) { recovery = !recovery }
                if (recovery) {
                    Text(localizedUiText("Send a reset code to the verified account owner's email. It expires in 10 minutes."))
                    action(localizedUiText("Send recovery code")) { vm.requestEmailRecovery() }
                    state.recoveryDestination?.let { destination ->
                        Text(localizedUiText("Code sent to $destination"))
                        pinField(localizedUiText("6-digit recovery code"), code) { code = it }
                        pinField(localizedUiText("New parent PIN"), replacement) { replacement = it }
                        pinField(localizedUiText("Confirm new PIN"), confirmation) { confirmation = it }
                        action(localizedUiText("Reset parent PIN"), code.length == 6 && replacement.length >= 4 && replacement == confirmation) {
                            vm.confirmEmailRecovery(code, replacement, confirmation); code = ""; replacement = ""; confirmation = ""
                        }
                    }
                }
                if (state.access.parentAuthorized && lockedProfilePicker) action(localizedUiText("Turn off Family controls")) { confirmDisable = true }
            }
            state.editingProfileId != null -> {
                Text(localizedUiText(state.profiles.firstOrNull { it.id == state.editingProfileId }?.name ?: "Profile"), style = MaterialTheme.typography.titleLarge)
                action(localizedUiText(if (state.draftKids) "Profile type: Kids" else "Profile type: Adult · PIN protected")) { vm.setKids(!state.draftKids) }
                if (state.draftKids) {
                    Text(localizedUiText("Rating country"), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().horizontalScrollState(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("US", "DE", "GB", "FR", "AU").forEach { country -> FilterChip(state.draftRules.ratingCountry == country, { vm.setCountry(country) }, { Text(country) }) }
                    }
                    Text(localizedUiText("Maximum age · ${state.draftRules.maximumAge}"))
                    Row(Modifier.fillMaxWidth().horizontalScrollState(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 6, 7, 8, 10, 12, 13, 14, 15, 16, 17).forEach { age -> FilterChip(state.draftRules.maximumAge == age, { vm.setMaximumAge(age) }, { Text(localizedUiText(if (age == 0) "All ages" else "$age")) }) }
                    }
                    Row { Checkbox(state.draftRules.hideUnrated, vm::setHideUnrated); Text(localizedUiText("Hide titles without an age rating"), Modifier.padding(top = 12.dp)) }
                    Text(localizedUiText("Allowed libraries & sources"), style = MaterialTheme.typography.titleMedium)
                    Text(localizedUiText("Only checked sources appear. Approve live groups only if their entire schedule is suitable."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.sources.forEach { source ->
                        action((if (source.key in state.draftRules.allowedSources) "✓ " else "○ ") + source.title + " · " + source.group) { vm.toggleSource(source.key) }
                    }
                    Text(localizedUiText("Blocked titles"), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(query, { query = it }, label = { Text(localizedUiText("Find a movie or show")) }, modifier = Modifier.fillMaxWidth())
                    action(localizedUiText("Find titles"), query.trim().length >= 2) { vm.searchTitles(query) }
                    state.titleResults.forEach { item -> action((if ("${item.type.name}:${item.id}" in state.draftRules.blockedTitles) "✓ " else "+ ") + item.title) { vm.toggleBlocked(item) } }
                    state.draftRules.blockedTitles.forEach { key -> action(localizedUiText("Unblock ${state.blockedTitleLabels[key] ?: "Blocked title"}")) { vm.removeBlocked(key) } }
                    action(localizedUiText("Preview as child")) { vm.preview() }
                    if (state.previewReady) {
                        Text(localizedUiText("Catalogue preview · ${state.previewItems.size} titles allowed"), style = MaterialTheme.typography.titleMedium)
                        if (state.previewItems.isEmpty()) Text(localizedUiText("No preview titles match these rules. Check the selected sources and age limit."))
                        state.previewItems.forEach { Text(it.title) }
                    }
                }
                action(localizedUiText("Save profile rules")) { vm.saveEditor() }
                action(localizedUiText("Cancel")) { vm.cancelEdit() }
            }
            else -> {
                action(localizedUiText("Copy connections between profiles")) { vm.beginCopyConnections() }
                Text(localizedUiText("On · adult profiles locked"), style = MaterialTheme.typography.titleMedium)
                state.profiles.forEach { profile ->
                    val rules = state.access.configuration.kids[profile.id]
                    action(localizedUiText(profile.name + if (rules == null) " · Adult · PIN protected" else " · Kids · up to ${rules.maximumAge} · ${rules.allowedSources.size} sources")) { vm.beginEdit(profile.id) }
                }
                OutlinedTextField(name, { name = it }, label = { Text(localizedUiText("New kids profile name")) }, modifier = Modifier.fillMaxWidth())
                action(localizedUiText("Add kids profile"), name.trim().isNotEmpty()) { vm.createProfile(name); name = "" }
                pinField(localizedUiText("New parent PIN"), pin) { pin = it }; pinField(localizedUiText("Confirm new PIN"), confirmation) { confirmation = it }
                action(localizedUiText("Change parent PIN"), pin.length >= 4 && pin == confirmation) { vm.changePin(pin, confirmation); pin = ""; confirmation = "" }
                action(localizedUiText("Lock adult profiles now")) { vm.lock() }
                action(localizedUiText("Turn off Family controls")) { confirmDisable = true }
            }
        }
        state.access.syncError?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.access.syncedAt != null) Text(localizedUiText("Family settings synced"))
        action(localizedUiText("Refresh profiles & rules")) { vm.refresh() }
        if (state.busy) CircularProgressIndicator()
        state.error?.let { Text(localizedUiText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("family.error")) }
        state.notice?.let { Text(localizedUiText(it)) }
    } }
    if (confirmDisable) AlertDialog(onDismissRequest = { confirmDisable = false }, title = { Text(localizedUiText("Turn off Family controls?")) },
        text = { Text(localizedUiText("Family restrictions turn off across signed-in devices. Other content settings still apply.")) },
        confirmButton = { TextButton({ confirmDisable = false; vm.disable() }) { Text(localizedUiText("Turn off")) } },
        dismissButton = { TextButton({ confirmDisable = false }) { Text(localizedUiText("Cancel")) } })
}

@Composable private fun Modifier.horizontalScrollState(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))
