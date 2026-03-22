package com.torve.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Ruby
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.presentation.mdblist.MdbListTab
import com.torve.presentation.mdblist.MdbListViewModel
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@Composable
fun MdbListSettingsScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = koinInject(),
    mdbListViewModel: MdbListViewModel = koinInject(),
) {
    val settingsState by settingsViewModel.state.collectAsState()
    val mdbState by mdbListViewModel.state.collectAsState()
    val authClient: com.torve.data.auth.AuthClient = koinInject()
    val authUser by authClient.authUserFlow.collectAsState()
    var apiKeyInput by remember { mutableStateOf(settingsState.mdblistApiKey) }
    val accountSessionCoordinator: com.torve.presentation.session.AccountSessionCoordinator = koinInject()
    val mdbScope = rememberCoroutineScope()
    val defaultStorageMode = if (authUser != null) com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT
        else com.torve.domain.integrations.IntegrationStorageMode.DEVICE_ONLY
    var mdblistStorageMode by remember(defaultStorageMode) { mutableStateOf(defaultStorageMode) }
    var showSaved by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Text(
                stringResource(R.string.mdblist_title),
                style = MaterialTheme.typography.titleLarge,
                color = Snow,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // API key
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_api_key), style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.mdblist_api_key_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
                Spacer(Modifier.height(8.dp))
                var apiKeyRevealed by remember { mutableStateOf(false) }
                val apiKeyPeek = com.torve.android.ui.components.rememberPeekPasswordTransformation(apiKeyInput)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.mdblist_enter_key), color = Steel) },
                        singleLine = true,
                        visualTransformation = if (apiKeyRevealed) VisualTransformation.None else apiKeyPeek,
                        trailingIcon = if (apiKeyInput.isNotEmpty()) {
                            {
                                IconButton(onClick = { apiKeyRevealed = !apiKeyRevealed }) {
                                    Icon(
                                        imageVector = if (apiKeyRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (apiKeyRevealed) "Hide" else "Show",
                                        tint = Silver,
                                    )
                                }
                            }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Snow,
                            unfocusedTextColor = Snow,
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = Gunmetal,
                            cursorColor = Amber,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        settingsViewModel.setMdblistApiKey(apiKeyInput)
                        mdbListViewModel.refreshApiKey()
                        showSaved = true
                        if (mdblistStorageMode == com.torve.domain.integrations.IntegrationStorageMode.ACCOUNT) {
                            mdbScope.launch {
                                accountSessionCoordinator.saveIntegrationToBackend(
                                    integrationType = "MDBLIST_API_KEY",
                                    credentials = mapOf("api_key" to apiKeyInput),
                                    displayIdentifier = "MDBList",
                                )
                            }
                        }
                    }) {
                        Text(
                            if (showSaved) stringResource(R.string.mdblist_saved) else stringResource(R.string.mdblist_save),
                            color = Amber,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (showSaved) {
                    LaunchedEffect(Unit) {
                        delay(2000)
                        showSaved = false
                    }
                }
                Spacer(Modifier.height(4.dp))
                com.torve.android.ui.components.StorageModeSelector(
                    selected = mdblistStorageMode,
                    onModeSelected = { mdblistStorageMode = it },
                    isSignedIn = authUser != null,
                )
            }

            // Tabs: Popular / Search
            item {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mdbState.activeTab == MdbListTab.POPULAR,
                        onClick = { mdbListViewModel.setActiveTab(MdbListTab.POPULAR) },
                        label = { Text(stringResource(R.string.mdblist_popular)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = Obsidian,
                            containerColor = Gunmetal,
                            labelColor = Snow,
                        ),
                    )
                    FilterChip(
                        selected = mdbState.activeTab == MdbListTab.SEARCH,
                        onClick = { mdbListViewModel.setActiveTab(MdbListTab.SEARCH) },
                        label = { Text(stringResource(R.string.common_search)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = Obsidian,
                            containerColor = Gunmetal,
                            labelColor = Snow,
                        ),
                    )
                }
            }

            when (mdbState.activeTab) {
                MdbListTab.POPULAR -> {
                    // Top / Popular lists
                    item {
                        Text(stringResource(R.string.mdblist_most_popular), style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
                        if (mdbState.topLists.isEmpty() && !mdbState.isLoadingTop && mdbState.apiKey.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { mdbListViewModel.loadTopLists() }) {
                                Text(stringResource(R.string.mdblist_load_popular), color = Amber)
                            }
                        }
                        if (mdbState.isLoadingTop) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Amber)
                        }
                    }

                    if (mdbState.topLists.isNotEmpty()) {
                        items(mdbState.topLists, key = { "top_${it.id}" }) { listInfo ->
                            ListInfoRow(
                                listInfo = listInfo,
                                isAdded = mdbState.savedLists.any { it.listId == listInfo.id },
                                onAdd = { mdbListViewModel.addList(listInfo.id, listInfo.name) },
                            )
                        }
                    }
                }

                MdbListTab.SEARCH -> {
                    // Search
                    item {
                        Text(stringResource(R.string.mdblist_search_lists), style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        var searchInput by remember { mutableStateOf(mdbState.searchQuery) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = searchInput,
                                onValueChange = {
                                    searchInput = it
                                    mdbListViewModel.setSearchQuery(it)
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.mdblist_search_placeholder), color = Steel) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Snow,
                                    unfocusedTextColor = Snow,
                                    focusedBorderColor = Amber,
                                    unfocusedBorderColor = Gunmetal,
                                    cursorColor = Amber,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { mdbListViewModel.search() }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.common_search), tint = Amber)
                            }
                        }
                        if (mdbState.isSearching) {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Amber)
                        }
                    }

                    // Search results
                    if (mdbState.searchResults.isNotEmpty()) {
                        item {
                            Text(stringResource(R.string.mdblist_search_results), style = MaterialTheme.typography.titleSmall, color = Silver)
                        }
                        items(mdbState.searchResults, key = { "sr_${it.id}" }) { listInfo ->
                            ListInfoRow(
                                listInfo = listInfo,
                                isAdded = mdbState.savedLists.any { it.listId == listInfo.id },
                                onAdd = { mdbListViewModel.addList(listInfo.id, listInfo.name) },
                            )
                        }
                    }
                }
            }

            // Installed lists (always visible)
            item {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.mdblist_installed), style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
                if (mdbState.savedLists.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.mdblist_no_lists), color = Silver, style = MaterialTheme.typography.bodySmall)
                }
            }

            items(mdbState.savedLists, key = { "saved_${it.listId}" }) { config ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Gunmetal, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(config.name, color = Snow, fontWeight = FontWeight.Medium)
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { mdbListViewModel.toggleList(config.listId, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Snow,
                            checkedTrackColor = Amber,
                            uncheckedTrackColor = Gunmetal,
                        ),
                    )
                    IconButton(onClick = { mdbListViewModel.removeList(config.listId) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_remove), tint = Ruby)
                    }
                }
            }

            // Error
            mdbState.error?.let { error ->
                item {
                    Text(error, color = Ruby, style = MaterialTheme.typography.bodySmall)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ListInfoRow(
    listInfo: com.torve.domain.model.MdbListInfo,
    isAdded: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Gunmetal, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(listInfo.name, color = Snow, fontWeight = FontWeight.Medium)
            if (listInfo.description.isNotBlank()) {
                Text(
                    listInfo.description.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    maxLines = 2,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.mdblist_items, listInfo.items),
                    style = MaterialTheme.typography.bodySmall,
                    color = Steel,
                )
                if (listInfo.userName.isNotBlank()) {
                    Text(
                        stringResource(R.string.mdblist_by, listInfo.userName),
                        style = MaterialTheme.typography.bodySmall,
                        color = Steel,
                    )
                }
                if (listInfo.likes > 0) {
                    Text(
                        stringResource(R.string.mdblist_likes, listInfo.likes),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber.copy(alpha = 0.7f),
                    )
                }
            }
        }
        if (!isAdded) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.common_add), tint = Amber)
            }
        } else {
            Text(stringResource(R.string.mdblist_added), color = Silver, style = MaterialTheme.typography.bodySmall)
        }
    }
}
