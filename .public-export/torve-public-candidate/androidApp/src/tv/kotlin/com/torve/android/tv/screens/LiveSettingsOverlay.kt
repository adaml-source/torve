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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.torve.domain.player.LiveAudioOutputMode
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
    onSetPictureFormat: (String) -> Unit,
    onSetXxxEnabled: (Boolean) -> Unit,
    onSetAudioPassthroughEnabled: (Boolean) -> Unit,
    onSetPreferSurroundCodecs: (Boolean) -> Unit,
    onSetLiveAudioOutputMode: (LiveAudioOutputMode) -> Unit,
    audioDelayMs: Int = 0,
    onSetAudioDelay: (Int) -> Unit = {},
    audioTracks: List<com.torve.domain.player.TrackDescription> = emptyList(),
    subtitleTracks: List<com.torve.domain.player.TrackDescription> = emptyList(),
    onSelectAudioTrack: (Int) -> Unit = {},
    onSelectSubtitleTrack: (Int) -> Unit = {},
    onDisableSubtitles: () -> Unit = {},
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

            // ── Section 4: Adult Content ──
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
            item(key = "audio_mode") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val options = listOf(
                        LiveAudioOutputMode.AUTO to stringResource(R.string.tv_live_audio_mode_auto),
                        LiveAudioOutputMode.PREFER_COMPATIBLE to stringResource(R.string.tv_live_audio_mode_compatible),
                        LiveAudioOutputMode.FORCE_STEREO_PCM to stringResource(R.string.tv_live_audio_mode_stereo),
                    )
                    options.forEach { (mode, label) ->
                        val selected = state.liveAudioOutputMode == mode
                        Surface(
                            onClick = { onSetLiveAudioOutputMode(mode) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) AmberSubtle else Graphite,
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
                                text = label,
                                color = if (selected) Amber else Snow,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
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

            // ── Audio Delay ──
            item(key = "audio_delay") {
                Surface(
                    onClick = { /* toggle handled by slider */ },
                    modifier = Modifier.fillMaxWidth(),
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.player_audio_delay),
                                color = Snow,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = stringResource(R.string.player_audio_delay_value, audioDelayMs),
                                color = if (audioDelayMs != 0) Amber else Silver,
                                fontSize = 13.sp,
                                fontWeight = if (audioDelayMs != 0) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompactButton(label = "-100") { onSetAudioDelay((audioDelayMs - 100).coerceIn(-2000, 2000)) }
                            Slider(
                                value = audioDelayMs.toFloat(),
                                onValueChange = { onSetAudioDelay(it.toInt()) },
                                valueRange = -2000f..2000f,
                                steps = 39,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Amber,
                                    activeTrackColor = Amber,
                                    inactiveTrackColor = Charcoal,
                                ),
                            )
                            CompactButton(label = "+100") { onSetAudioDelay((audioDelayMs + 100).coerceIn(-2000, 2000)) }
                        }
                        if (audioDelayMs != 0) {
                            Spacer(Modifier.height(4.dp))
                            CompactButton(label = "Reset") { onSetAudioDelay(0) }
                        }
                    }
                }
            }

            // ── Audio Track Picker ──
            if (audioTracks.size > 1) {
                item(key = "audio_track_header") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("Audio Track")
                }
                items(audioTracks, key = { "atrack_${it.id}" }) { track ->
                    val label = track.label.ifBlank { track.language ?: "Track ${track.id}" }
                    Surface(
                        onClick = { onSelectAudioTrack(track.id) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (track.isSelected) Amber.copy(alpha = 0.2f) else Graphite,
                            focusedContainerColor = Amber.copy(alpha = 0.3f),
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
                            Text(
                                text = label,
                                color = if (track.isSelected) Amber else Snow,
                                fontSize = 14.sp,
                                fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            // ── Subtitle Track Picker ──
            if (subtitleTracks.isNotEmpty()) {
                item(key = "subtitle_track_header") {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("Subtitles")
                }
                item(key = "strack_off") {
                    val noSubSelected = subtitleTracks.none { it.isSelected }
                    Surface(
                        onClick = { onDisableSubtitles() },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (noSubSelected) Amber.copy(alpha = 0.2f) else Graphite,
                            focusedContainerColor = Amber.copy(alpha = 0.3f),
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
                            Text(
                                text = "Off",
                                color = if (noSubSelected) Amber else Snow,
                                fontSize = 14.sp,
                                fontWeight = if (noSubSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                items(subtitleTracks, key = { "strack_${it.id}" }) { track ->
                    val label = track.label.ifBlank { track.language ?: "Track ${track.id}" }
                    Surface(
                        onClick = { onSelectSubtitleTrack(track.id) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (track.isSelected) Amber.copy(alpha = 0.2f) else Graphite,
                            focusedContainerColor = Amber.copy(alpha = 0.3f),
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
                            Text(
                                text = label,
                                color = if (track.isSelected) Amber else Snow,
                                fontSize = 14.sp,
                                fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
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
