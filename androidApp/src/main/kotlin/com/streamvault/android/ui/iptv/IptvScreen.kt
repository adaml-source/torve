package com.streamvault.android.ui.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.domain.model.EnrichedChannel
import com.streamvault.domain.model.IptvChannel
import com.streamvault.presentation.iptv.IptvViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvScreen(
    onChannelPlay: (IptvChannel) -> Unit,
    viewModel: IptvViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.playlists.isEmpty() && !state.isLoading) {
            // Empty state — no playlists added
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No IPTV Playlists",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Add an M3U or Xtream Codes playlist to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { viewModel.showAddPlaylistDialog() }) {
                    Text("Add Playlist")
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search bar
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = state.searchQuery,
                            onQueryChange = { viewModel.updateSearchQuery(it) },
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text("Search channels...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.clearSearch() }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {}

                // Playlist selector + actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.playlists) { playlist ->
                            FilterChip(
                                selected = state.selectedPlaylistId == playlist.id,
                                onClick = { viewModel.selectPlaylist(playlist.id) },
                                label = { Text(playlist.name) },
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleCountryFilter() }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Country Filter")
                    }
                    IconButton(onClick = { viewModel.refreshPlaylist() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }

                // Country filter + XXX toggle row
                if (state.showCountryFilter) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Allow Adult (XXX)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = state.xxxEnabled,
                                onCheckedChange = { viewModel.setXxxEnabled(it) },
                            )
                        }
                        if (state.availableCountries.isNotEmpty()) {
                            Text(
                                "Filter by Country:",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    FilterChip(
                                        selected = state.selectedCountries.isEmpty(),
                                        onClick = { viewModel.clearCountryFilter() },
                                        label = { Text("All") },
                                    )
                                }
                                items(state.availableCountries) { country ->
                                    FilterChip(
                                        selected = country in state.selectedCountries,
                                        onClick = { viewModel.toggleCountry(country) },
                                        label = { Text(country) },
                                    )
                                }
                            }
                        }
                    }
                }

                // Group filter chips
                if (state.groupedChannels.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedGroup == null,
                                onClick = { viewModel.selectGroup(null) },
                                label = { Text("All (${state.channels.size})") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.selectedGroup == "★ Favorites",
                                onClick = { viewModel.selectGroup("★ Favorites") },
                                label = { Text("★ Favorites") },
                            )
                        }
                        items(state.groupedChannels.keys.sorted().toList()) { group ->
                            val count = state.groupedChannels[group]?.size ?: 0
                            FilterChip(
                                selected = state.selectedGroup == group,
                                onClick = { viewModel.selectGroup(group) },
                                label = { Text("$group ($count)") },
                            )
                        }
                    }
                }

                // Channel grid
                if (state.isLoadingChannels) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    }
                } else {
                    val displayChannels = when {
                        state.searchQuery.length >= 2 -> state.searchResults.map { EnrichedChannel(it) }
                        state.selectedGroup == "★ Favorites" -> state.favorites.map { EnrichedChannel(it) }
                        else -> viewModel.getDisplayChannels()
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(displayChannels, key = { "${it.channel.playlistId}_${it.channel.name}" }) { enriched ->
                            ChannelCard(
                                enriched = enriched,
                                onPlay = { onChannelPlay(enriched.channel) },
                                onFavorite = { viewModel.toggleFavorite(enriched.channel) },
                            )
                        }
                    }
                }
            }
        }

        // FAB to add playlist
        if (state.playlists.isNotEmpty()) {
            FloatingActionButton(
                onClick = { viewModel.showAddPlaylistDialog() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Playlist")
            }
        }

        // Add playlist dialog
        if (state.showAddPlaylist) {
            AddPlaylistDialog(
                name = state.newPlaylistName,
                url = state.newPlaylistUrl,
                epgUrl = state.newPlaylistEpgUrl,
                playlistType = state.newPlaylistType,
                xtreamServer = state.newXtreamServer,
                xtreamUsername = state.newXtreamUsername,
                xtreamPassword = state.newXtreamPassword,
                isLoading = state.isAddingPlaylist,
                onNameChange = { viewModel.setNewPlaylistName(it) },
                onUrlChange = { viewModel.setNewPlaylistUrl(it) },
                onEpgUrlChange = { viewModel.setNewPlaylistEpgUrl(it) },
                onTypeChange = { viewModel.setNewPlaylistType(it) },
                onXtreamServerChange = { viewModel.setNewXtreamServer(it) },
                onXtreamUsernameChange = { viewModel.setNewXtreamUsername(it) },
                onXtreamPasswordChange = { viewModel.setNewXtreamPassword(it) },
                onConfirm = { viewModel.addPlaylist() },
                onDismiss = { viewModel.dismissAddPlaylistDialog() },
            )
        }
    }
}

@Composable
private fun ChannelCard(
    enriched: EnrichedChannel,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val channel = enriched.channel

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Channel logo
                if (channel.tvgLogo != null) {
                    AsyncImage(
                        model = channel.tvgLogo,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = channel.name.take(2).uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    channel.groupTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Current programme
            enriched.currentProgramme?.let { prog ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Now: ${prog.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            enriched.nextProgramme?.let { prog ->
                Text(
                    text = "Next: ${prog.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (channel.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (channel.isFavorite) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPlaylistDialog(
    name: String,
    url: String,
    epgUrl: String,
    playlistType: String,
    xtreamServer: String,
    xtreamUsername: String,
    xtreamPassword: String,
    isLoading: Boolean,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onEpgUrlChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onXtreamServerChange: (String) -> Unit,
    onXtreamUsernameChange: (String) -> Unit,
    onXtreamPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isXtream = playlistType == "xtream"
    val canConfirm = !isLoading && name.isNotBlank() && if (isXtream) {
        xtreamServer.isNotBlank() && xtreamUsername.isNotBlank() && xtreamPassword.isNotBlank()
    } else {
        url.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add IPTV Playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Type selector
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isXtream,
                        onClick = { onTypeChange("m3u") },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("M3U") }
                    SegmentedButton(
                        selected = isXtream,
                        onClick = { onTypeChange("xtream") },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Xtream Codes") }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isXtream) {
                    OutlinedTextField(
                        value = xtreamServer,
                        onValueChange = onXtreamServerChange,
                        label = { Text("Server URL") },
                        placeholder = { Text("http://example.com:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = xtreamUsername,
                        onValueChange = onXtreamUsernameChange,
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = xtreamPassword,
                        onValueChange = onXtreamPasswordChange,
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        label = { Text("M3U URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = epgUrl,
                        onValueChange = onEpgUrlChange,
                        label = { Text("EPG URL (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
