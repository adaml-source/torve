package com.torve.android.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelPlaylist
import com.torve.presentation.channels.ChannelsUiState

data class LivePictureFormatOption(
    val key: String,
    val label: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSettingsOverlay(
    state: ChannelsUiState,
    currentChannel: Channel?,
    pictureFormats: List<LivePictureFormatOption>,
    selectedPictureFormatKey: String,
    onSelectPlaylist: (String) -> Unit,
    onToggleCountry: (String) -> Unit,
    onSelectAllCountries: () -> Unit,
    onClearAllCountries: () -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onToggleHiddenCategory: (String) -> Unit,
    onSetPictureFormat: (String) -> Unit,
    onSetXxxEnabled: (Boolean) -> Unit,
    onSetAudioPassthroughEnabled: (Boolean) -> Unit,
    onSetPreferSurroundCodecs: (Boolean) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { firstFocus.requestFocus() } catch (_: IllegalStateException) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian.copy(alpha = 0.95f)),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Title
            item(key = "title") {
                Text(
                    text = stringResource(R.string.tv_live_settings),
                    color = Snow,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
            }

            // ── Section 1: Playlists ──
            item(key = "playlists_header") {
                SectionHeader(stringResource(R.string.tv_live_playlists))
            }
            items(state.playlists, key = { "pl_${it.id}" }) { playlist ->
                val isActive = playlist.id == state.selectedPlaylistId
                val isFirst = playlist == state.playlists.firstOrNull()
                PlaylistRow(
                    playlist = playlist,
                    isActive = isActive,
                    focusRequester = if (isFirst) firstFocus else null,
                    onSelect = { onSelectPlaylist(playlist.id) },
                )
            }

            // ── Section 2: Countries ──
            if (state.availableCountries.isNotEmpty()) {
                item(key = "countries_header") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(stringResource(R.string.tv_live_countries))
                }
                item(key = "country_actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompactButton(
                            label = stringResource(R.string.tv_live_select_all),
                            onClick = onSelectAllCountries,
                        )
                        CompactButton(
                            label = stringResource(R.string.tv_live_clear_all),
                            onClick = onClearAllCountries,
                        )
                    }
                }
                item(key = "country_chips") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.availableCountries.forEach { code ->
                            val isSelected = code in state.selectedCountries
                            Surface(
                                onClick = { onToggleCountry(code) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) AmberSubtle else Graphite,
                                    focusedContainerColor = Amber.copy(alpha = 0.3f),
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = androidx.tv.material3.Border(
                                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                                        shape = RoundedCornerShape(8.dp),
                                    ),
                                ),
                            ) {
                                Text(
                                    text = code.uppercase(),
                                    color = if (isSelected) Amber else Snow,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── Section 3: Current Channel ──
            if (currentChannel != null) {
                item(key = "current_header") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(stringResource(R.string.tv_live_current_channel))
                }
                item(key = "current_fav") {
                    val isFav = state.favorites.any { it.url == currentChannel.url }
                    Surface(
                        onClick = { onToggleFavorite(currentChannel) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Graphite,
                            focusedContainerColor = Amber.copy(alpha = 0.2f),
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                                shape = RoundedCornerShape(8.dp),
                            ),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = null,
                                tint = if (isFav) Amber else Silver,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (isFav) {
                                    stringResource(R.string.tv_live_remove_favorite, currentChannel.name)
                                } else {
                                    stringResource(R.string.tv_live_add_favorite, currentChannel.name)
                                },
                                color = Snow,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }

            // ── Section 4: Category Visibility ──
            if (state.allCategories.isNotEmpty()) {
                item(key = "cat_header") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(stringResource(R.string.tv_live_category_visibility))
                }
                items(
                    state.allCategories.take(30),
                    key = { "cat_${it.name}" },
                ) { category ->
                    val catName = category.name
                    val isHidden = catName in state.hiddenCategories
                    Surface(
                        onClick = { onToggleHiddenCategory(catName) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Graphite.copy(alpha = 0.6f),
                            focusedContainerColor = Amber.copy(alpha = 0.2f),
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(1.dp, Amber),
                                shape = RoundedCornerShape(6.dp),
                            ),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = catName,
                                color = if (isHidden) Silver.copy(alpha = 0.5f) else Snow,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = if (isHidden) {
                                    stringResource(R.string.tv_live_hidden)
                                } else {
                                    stringResource(R.string.tv_live_visible)
                                },
                                color = if (isHidden) Silver.copy(alpha = 0.5f) else Amber,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            // ── Section 5: Adult Content ──
            if (pictureFormats.isNotEmpty()) {
                item(key = "picture_header") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("Picture format")
                }
                item(key = "picture_modes") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pictureFormats.forEach { option ->
                            val isSelected = option.key == selectedPictureFormatKey
                            Surface(
                                onClick = { onSetPictureFormat(option.key) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) AmberSubtle else Graphite,
                                    focusedContainerColor = Amber.copy(alpha = 0.3f),
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = androidx.tv.material3.Border(
                                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                                        shape = RoundedCornerShape(8.dp),
                                    ),
                                ),
                            ) {
                                Text(
                                    text = option.label,
                                    color = if (isSelected) Amber else Snow,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "audio_header") {
                Spacer(Modifier.height(8.dp))
                SectionHeader(stringResource(R.string.tv_live_audio_output))
            }
            item(key = "audio_passthrough") {
                Surface(
                    onClick = { onSetAudioPassthroughEnabled(!state.audioPassthroughEnabled) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Graphite,
                        focusedContainerColor = Amber.copy(alpha = 0.2f),
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                            shape = RoundedCornerShape(8.dp),
                        ),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.tv_live_audio_passthrough),
                                color = Snow,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = stringResource(R.string.tv_live_audio_passthrough_desc),
                                color = Silver,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = state.audioPassthroughEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Amber,
                                checkedTrackColor = AmberSubtle,
                                uncheckedThumbColor = Silver,
                                uncheckedTrackColor = Charcoal,
                            ),
                        )
                    }
                }
            }
            item(key = "audio_surround") {
                Surface(
                    onClick = { onSetPreferSurroundCodecs(!state.preferSurroundCodecs) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Graphite,
                        focusedContainerColor = Amber.copy(alpha = 0.2f),
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                            shape = RoundedCornerShape(8.dp),
                        ),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.tv_live_audio_prefer_surround),
                                color = Snow,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = stringResource(R.string.tv_live_audio_prefer_surround_desc),
                                color = Silver,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = state.preferSurroundCodecs,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Amber,
                                checkedTrackColor = AmberSubtle,
                                uncheckedThumbColor = Silver,
                                uncheckedTrackColor = Charcoal,
                            ),
                        )
                    }
                }
            }

            item(key = "adult_header") {
                Spacer(Modifier.height(8.dp))
                SectionHeader(stringResource(R.string.tv_live_adult_content))
            }
            item(key = "adult_toggle") {
                Surface(
                    onClick = { onSetXxxEnabled(!state.xxxEnabled) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Graphite,
                        focusedContainerColor = Amber.copy(alpha = 0.2f),
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                            shape = RoundedCornerShape(8.dp),
                        ),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.tv_live_show_adult),
                            color = Snow,
                            fontSize = 14.sp,
                        )
                        Switch(
                            checked = state.xxxEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Amber,
                                checkedTrackColor = AmberSubtle,
                                uncheckedThumbColor = Silver,
                                uncheckedTrackColor = Charcoal,
                            ),
                        )
                    }
                }
            }

            // Bottom spacer
            item(key = "spacer") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Amber,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun PlaylistRow(
    playlist: ChannelPlaylist,
    isActive: Boolean,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isActive) AmberSubtle else Graphite,
            focusedContainerColor = Amber.copy(alpha = 0.25f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                shape = RoundedCornerShape(8.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = playlist.name,
                    color = if (isActive) Amber else Snow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${playlist.channelCount} channels",
                    color = Silver,
                    fontSize = 11.sp,
                )
            }
            if (isActive) {
                Text(
                    text = stringResource(R.string.tv_live_active),
                    color = Amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CompactButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Graphite,
            focusedContainerColor = Amber.copy(alpha = 0.25f),
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Amber),
                shape = RoundedCornerShape(8.dp),
            ),
        ),
    ) {
        Text(
            text = label,
            color = Snow,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
