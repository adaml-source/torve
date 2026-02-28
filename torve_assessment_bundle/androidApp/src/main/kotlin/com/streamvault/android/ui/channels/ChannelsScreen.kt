package com.streamvault.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.streamvault.android.R
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.Channel
import com.streamvault.presentation.channels.ChannelsSubTab
import com.streamvault.presentation.channels.ChannelsViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onChannelPlay: (Channel) -> Unit,
    viewModel: ChannelsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (state.playlists.isEmpty() && !state.isLoading) {
            // ── Empty State ──
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.LiveTv,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = StreamVault.colors.textHint,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.channels_no_playlists),
                    style = MaterialTheme.typography.headlineSmall,
                    color = StreamVault.colors.textPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.channels_add_playlist_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamVault.colors.textTertiary,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.showAddPlaylistDialog() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = Obsidian,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.channels_add_playlist), fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(), // ← KEY FIX: push content below status bar
            ) {
                // ── Playlist Selector Row (only shown if multiple playlists) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.playlists.size > 1) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(state.playlists) { playlist ->
                                val selected = state.selectedPlaylistId == playlist.id
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.selectPlaylist(playlist.id) },
                                    label = {
                                        Text(
                                            playlist.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) Amber else Snow,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AmberSubtle,
                                        selectedLabelColor = Amber,
                                        containerColor = Gunmetal,
                                        labelColor = Snow,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = MaterialTheme.colorScheme.background,
                                        selectedBorderColor = Amber.copy(alpha = 0.3f),
                                        enabled = true,
                                        selected = selected,
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = { viewModel.toggleCategoryManager() }) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.channels_manage_categories),
                            tint = StreamVault.colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { viewModel.refreshPlaylist() }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.channels_refresh),
                            tint = StreamVault.colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // ── Sub-Tab Bar ──
                ChannelsSubTabBar(
                    selectedTab = state.selectedSubTab,
                    onTabSelected = { viewModel.selectSubTab(it) },
                )

                // ── Content ──
                when (state.selectedSubTab) {
                    ChannelsSubTab.LIVE -> ChannelsLiveContent(
                        categories = state.categories,
                        expandedCategories = state.expandedCategories,
                        searchQuery = state.searchQuery,
                        searchResults = state.searchResults,
                        isLoading = state.isLoadingChannels,
                        viewMode = state.viewMode,
                        onToggleCategory = { viewModel.toggleCategoryExpanded(it) },
                        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                        onClearSearch = { viewModel.clearSearch() },
                        onToggleViewMode = { viewModel.toggleViewMode() },
                        onFilterClick = { viewModel.toggleFilterSheet() },
                        onChannelPlay = { channel ->
                            viewModel.recordChannelViewed(channel)
                            onChannelPlay(channel)
                        },
                        onChannelFavorite = { viewModel.toggleFavorite(it) },
                    )

                    ChannelsSubTab.FAVOURITES -> ChannelsFavouritesContent(
                        favorites = state.favorites,
                        onChannelPlay = { channel ->
                            viewModel.recordChannelViewed(channel)
                            onChannelPlay(channel)
                        },
                        onChannelFavorite = { viewModel.toggleFavorite(it) },
                    )
                }
            }
        }

        // ── Add Playlist Dialog ──
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

        // ── Filter Sheet ──
        if (state.showFilterSheet) {
            FilterBottomSheet(
                activeFilter = state.activeFilter,
                activeSort = state.activeSort,
                onFilterSelected = { viewModel.setFilter(it) },
                onSortSelected = { viewModel.setSort(it) },
                onDismiss = { viewModel.toggleFilterSheet() },
            )
        }

        // ── Category Manager Sheet ──
        if (state.showCategoryManager) {
            CategoryManagerSheet(
                categories = state.allCategories,
                hiddenCategories = state.hiddenCategories,
                onToggleCategory = { viewModel.toggleHiddenCategory(it) },
                onHideAll = { viewModel.hideAllCategories() },
                onShowAll = { viewModel.showAllCategories() },
                onHideCountry = { viewModel.hideCountryCategories(it) },
                onShowCountry = { viewModel.showCountryCategories(it) },
                onDismiss = { viewModel.toggleCategoryManager() },
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Add Playlist Dialog
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddPlaylistDialog(
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
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(
                stringResource(R.string.channels_add_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isXtream,
                        onClick = { onTypeChange("m3u") },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("M3U", color = MaterialTheme.colorScheme.onSurface) }
                    SegmentedButton(
                        selected = isXtream,
                        onClick = { onTypeChange("xtream") },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Provider Login", color = MaterialTheme.colorScheme.onSurface) }
                }

                StyledTextField(value = name, onValueChange = onNameChange, label = stringResource(R.string.channels_playlist_name))

                if (isXtream) {
                    StyledTextField(value = xtreamServer, onValueChange = onXtreamServerChange, label = stringResource(R.string.channels_server_url), placeholder = "http://example.com:8080")
                    StyledTextField(value = xtreamUsername, onValueChange = onXtreamUsernameChange, label = stringResource(R.string.channels_username))
                    StyledTextField(value = xtreamPassword, onValueChange = onXtreamPasswordChange, label = stringResource(R.string.channels_password))
                } else {
                    StyledTextField(value = url, onValueChange = onUrlChange, label = stringResource(R.string.channels_m3u_url))
                    StyledTextField(value = epgUrl, onValueChange = onEpgUrlChange, label = stringResource(R.string.channels_epg_optional))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = MaterialTheme.colorScheme.background,
                    disabledContainerColor = Amber.copy(alpha = 0.3f),
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.background,
                    )
                } else {
                    Text(stringResource(R.string.common_add), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = StreamVault.colors.textTertiary)
            }
        },
    )
}

@Composable
private fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = StreamVault.colors.textTertiary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Amber),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder ?: label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StreamVault.colors.textHint,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}
