package com.streamvault.android.ui.settings

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Ruby
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.presentation.mdblist.MdbListTab
import com.streamvault.presentation.mdblist.MdbListViewModel
import com.streamvault.presentation.settings.SettingsViewModel
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
    var apiKeyInput by remember { mutableStateOf(settingsState.mdblistApiKey) }
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
                "MDBList",
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
                Text("API Key", style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Get your free API key at mdblist.com/preferences",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter MDBList API key", color = Steel) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
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
                    }) {
                        Text(
                            if (showSaved) "Saved" else "Save",
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
            }

            // Tabs: Popular / Search
            item {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mdbState.activeTab == MdbListTab.POPULAR,
                        onClick = { mdbListViewModel.setActiveTab(MdbListTab.POPULAR) },
                        label = { Text("Popular") },
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
                        label = { Text("Search") },
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
                        Text("Most Popular Lists", style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
                        if (mdbState.topLists.isEmpty() && !mdbState.isLoadingTop && mdbState.apiKey.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { mdbListViewModel.loadTopLists() }) {
                                Text("Load popular lists", color = Amber)
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
                        Text("Search Lists", style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
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
                                placeholder = { Text("Search public lists...", color = Steel) },
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
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = Amber)
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
                            Text("Search Results", style = MaterialTheme.typography.titleSmall, color = Silver)
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
                Text("Installed Lists", style = MaterialTheme.typography.titleMedium, color = Snow, fontWeight = FontWeight.SemiBold)
                if (mdbState.savedLists.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("No lists added yet.", color = Silver, style = MaterialTheme.typography.bodySmall)
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
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Ruby)
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
    listInfo: com.streamvault.domain.model.MdbListInfo,
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
                    "${listInfo.items} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = Steel,
                )
                if (listInfo.userName.isNotBlank()) {
                    Text(
                        "by ${listInfo.userName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Steel,
                    )
                }
                if (listInfo.likes > 0) {
                    Text(
                        "${listInfo.likes} likes",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber.copy(alpha = 0.7f),
                    )
                }
            }
        }
        if (!isAdded) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Amber)
            }
        } else {
            Text("Added", color = Silver, style = MaterialTheme.typography.bodySmall)
        }
    }
}
