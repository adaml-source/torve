package com.torve.android.ui.channels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import com.torve.android.ui.components.MobileGlassOutlinedButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState
import com.torve.presentation.channels.userMessage

/** Guide reads the same bounded category data as Live, never the full playlist. */
@Composable
internal fun MobileChannelsGuide(
    state: ChannelsUiState,
    categories: List<ChannelCategory>,
    onGroupSelected: (String) -> Unit,
    onRetry: () -> Unit,
    onChannelPlay: (Channel) -> Unit,
) {
    var choosingGroup by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.name == state.selectedGroup }
    LaunchedEffect(state.selectedPlaylistId, categories.map { it.name }, state.selectedGroup) {
        if (selected == null) categories.firstOrNull()?.let { onGroupSelected(it.name) }
    }
    val channels = remember(state.guideChannels, state.selectedCountries) {
        state.guideChannels.filter { MobileChannelCountryFilter.matches(it.channel, state.selectedCountries) }
    }
    val loading = state.isLoadingChannels || state.isLoadingGuide || state.epgState is EpgState.Loading
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { choosingGroup = true }, enabled = categories.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("guide_group_picker")) {
            Text(selected?.let { "${it.countryCode.orEmpty()} ${channelCategoryLabel(it.name, it.countryCode)}".trim() }
                ?: stringResource(R.string.channels_guide_choose_group))
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("guide_loading"))
            Text(state.epgRefreshProgress?.userMessage() ?: stringResource(R.string.channels_guide_loading),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = Torve.colors.textSecondary)
        } else if (state.guideError != null || state.epgState is EpgState.Error || state.error != null) {
            Text(stringResource(R.string.channels_guide_failed), Modifier.padding(horizontal = 16.dp),
                color = Torve.colors.textSecondary)
            TextButton(onClick = { if (state.error != null && selected != null) onGroupSelected(selected.name) else onRetry() },
                modifier = Modifier.testTag("guide_retry")) { Text(stringResource(R.string.common_retry)) }
        } else if (state.epgState is EpgState.NotConfigured) {
            Text(stringResource(R.string.channels_no_epg_desc), Modifier.padding(16.dp),
                color = Torve.colors.textSecondary)
        }
        if (channels.isNotEmpty()) {
            ChannelsGuideContent(channels, state.guideProgrammes, false, onChannelPlay,
                modifier = Modifier.weight(1f))
        } else if (!loading && state.guideError == null && state.epgState !is EpgState.Error) {
            Text(stringResource(R.string.tv_iptv_no_channels_in_group), Modifier.padding(16.dp),
                color = Torve.colors.textSecondary)
        }
    }
    if (choosingGroup) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { choosingGroup = false },
            title = { Text(stringResource(R.string.channels_guide_choose_group)) },
            text = {
                Column {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.channels_guide_search_groups)) },
                        modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(categories.filter { it.name.contains(query.trim(), ignoreCase = true) }, key = { it.name }) { category ->
                            CategoryHeader(category.name, category.channelCount, category.qualityTags, false,
                                { choosingGroup = false; onGroupSelected(category.name) }, countryCode = category.countryCode)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingGroup = false }) { Text(stringResource(R.string.common_done)) } })
    }
}
