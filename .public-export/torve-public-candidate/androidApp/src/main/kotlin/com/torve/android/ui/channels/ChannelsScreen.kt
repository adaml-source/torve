package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.android.ui.theme.Charcoal
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.EnrichedChannel
import com.torve.presentation.channels.ChannelsSubTab
import com.torve.presentation.channels.ChannelsViewModel
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
                    tint = Torve.colors.textHint,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.channels_no_playlists),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Torve.colors.textPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.channels_add_playlist_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Torve.colors.textTertiary,
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
                            items(state.playlists, key = { it.id }) { playlist ->
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
                            tint = Torve.colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { viewModel.refreshPlaylist() }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.channels_refresh),
                            tint = Torve.colors.textSecondary,
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

                    // EPG grid surfaced as a top-level sub-tab. Was
                    // previously only reachable by starting playback
                    // and tapping the small TV icon overlay.
                    // selectSubTab() pre-builds guideProgrammes so the
                    // grid isn't empty on first switch.
                    ChannelsSubTab.GUIDE -> ChannelsGuideContent(
                        channels = state.channels,
                        guideProgrammes = state.guideProgrammes,
                        isLoading = state.isLoadingChannels && state.guideProgrammes.isEmpty(),
                        onChannelPlay = { channel ->
                            viewModel.recordChannelViewed(channel)
                            onChannelPlay(channel)
                        },
                    )

                    ChannelsSubTab.MOVIES -> VodMoviesContent(
                        categories = state.vodCategories,
                        expandedCategories = state.expandedVodCategories,
                        onToggleCategory = { viewModel.toggleVodCategoryExpanded(it) },
                        onChannelPlay = { channel ->
                            viewModel.recordChannelViewed(channel)
                            onChannelPlay(channel)
                        },
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
                isCheckingEpg = state.isCheckingEpg,
                epgCheckMessage = state.epgCheckMessage,
                epgCheckSuccess = state.epgCheckSuccess,
                onNameChange = { viewModel.setNewPlaylistName(it) },
                onUrlChange = { viewModel.setNewPlaylistUrl(it) },
                onEpgUrlChange = { viewModel.setNewPlaylistEpgUrl(it) },
                onTypeChange = { viewModel.setNewPlaylistType(it) },
                onXtreamServerChange = { viewModel.setNewXtreamServer(it) },
                onXtreamUsernameChange = { viewModel.setNewXtreamUsername(it) },
                onXtreamPasswordChange = { viewModel.setNewXtreamPassword(it) },
                onCheckEpg = { viewModel.checkNewPlaylistEpgUrl() },
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
    isCheckingEpg: Boolean,
    epgCheckMessage: String?,
    epgCheckSuccess: Boolean?,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onEpgUrlChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onXtreamServerChange: (String) -> Unit,
    onXtreamUsernameChange: (String) -> Unit,
    onXtreamPasswordChange: (String) -> Unit,
    onCheckEpg: () -> Unit,
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
                    ) { Text("URL", color = MaterialTheme.colorScheme.onSurface) }
                    SegmentedButton(
                        selected = isXtream,
                        onClick = { onTypeChange("xtream") },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.setup_provider_login), color = MaterialTheme.colorScheme.onSurface) }
                }

                StyledTextField(value = name, onValueChange = onNameChange, label = stringResource(R.string.channels_playlist_name))

                if (isXtream) {
                    StyledTextField(value = xtreamServer, onValueChange = onXtreamServerChange, label = stringResource(R.string.channels_server_url), placeholder = "http://example.com:8080")
                    StyledTextField(value = xtreamUsername, onValueChange = onXtreamUsernameChange, label = stringResource(R.string.channels_username))
                    StyledTextField(value = xtreamPassword, onValueChange = onXtreamPasswordChange, label = stringResource(R.string.channels_password), isSensitive = true)
                    StyledTextField(value = epgUrl, onValueChange = onEpgUrlChange, label = stringResource(R.string.channels_epg_optional))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onCheckEpg,
                            enabled = !isCheckingEpg && epgUrl.isNotBlank(),
                        ) {
                            Text(
                                if (isCheckingEpg) stringResource(R.string.channels_check_epg_checking)
                                else stringResource(R.string.channels_check_epg),
                                color = Amber,
                            )
                        }
                        if (isCheckingEpg) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Amber,
                            )
                        }
                    }
                    epgCheckMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (epgCheckSuccess == true) Amber else MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    StyledTextField(value = url, onValueChange = onUrlChange, label = stringResource(R.string.channels_m3u_url))
                    StyledTextField(value = epgUrl, onValueChange = onEpgUrlChange, label = stringResource(R.string.channels_epg_optional))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onCheckEpg,
                            enabled = !isCheckingEpg && epgUrl.isNotBlank(),
                        ) {
                            Text(
                                if (isCheckingEpg) stringResource(R.string.channels_check_epg_checking)
                                else stringResource(R.string.channels_check_epg),
                                color = Amber,
                            )
                        }
                        if (isCheckingEpg) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Amber,
                            )
                        }
                    }
                    epgCheckMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (epgCheckSuccess == true) Amber else MaterialTheme.colorScheme.error,
                        )
                    }
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
                Text(stringResource(R.string.common_cancel), color = Torve.colors.textTertiary)
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
    isSensitive: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    val peekTransformation = com.torve.android.ui.components.rememberPeekPasswordTransformation(value)
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Torve.colors.textTertiary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .padding(start = 12.dp, end = if (isSensitive) 4.dp else 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Amber),
                visualTransformation = if (isSensitive && !revealed) peekTransformation else VisualTransformation.None,
                keyboardOptions = if (isSensitive) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder ?: label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Torve.colors.textHint,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (isSensitive && value.isNotEmpty()) {
                IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (revealed) "Hide" else "Show",
                        tint = Torve.colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun VodMoviesContent(
    categories: List<ChannelCategory>,
    expandedCategories: Set<String>,
    onToggleCategory: (String) -> Unit,
    onChannelPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = Amber)
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        categories.forEach { category ->
            val expanded = category.name in expandedCategories
            item(key = "header_${category.name}") {
                CategoryHeader(
                    name = category.name,
                    channelCount = category.channelCount,
                    qualityTags = emptySet(),
                    isExpanded = expanded,
                    onToggle = { onToggleCategory(category.name) },
                )
            }
            if (expanded) {
                if (category.channels.isEmpty()) {
                    item(key = "loading_${category.name}") {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = Amber,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                } else {
                    item(key = "grid_${category.name}") {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(((category.channels.size / 3 + 1) * 180).coerceAtMost(540).dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp, vertical = 8.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            userScrollEnabled = false,
                        ) {
                            items(category.channels, key = { it.channel.url }) { enriched ->
                                VodPosterCard(
                                    channel = enriched.channel,
                                    onClick = { onChannelPlay(enriched.channel) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VodPosterCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Charcoal),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Column {
            AsyncImage(
                model = channel.tvgLogo,
                contentDescription = channel.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                contentScale = ContentScale.Crop,
                error = null,
            )
            androidx.compose.material3.Text(
                text = channel.name,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = Snow,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
