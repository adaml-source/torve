package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.theme.Torve
import com.torve.presentation.channels.ChannelsFilterType
import com.torve.presentation.channels.ChannelsSortType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    activeFilter: ChannelsFilterType,
    activeSort: ChannelsSortType,
    onFilterSelected: (ChannelsFilterType) -> Unit,
    onSortSelected: (ChannelsSortType) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Charcoal,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Steel, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.channels_filter),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChannelsFilterType.entries.forEach { filter ->
                    val selected = activeFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onFilterSelected(filter) },
                        label = {
                            Text(
                                when (filter) {
                                    ChannelsFilterType.ALL -> "All"
                                    ChannelsFilterType.HD -> "HD"
                                    ChannelsFilterType.FHD -> "FHD"
                                    ChannelsFilterType.UHD -> "4K / UHD"
                                    ChannelsFilterType.FAVORITES -> "Favorites"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = Obsidian,
                            containerColor = Gunmetal,
                            labelColor = Torve.colors.textSecondary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Charcoal,
                            selectedBorderColor = Amber,
                            enabled = true,
                            selected = selected,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.channels_sort),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChannelsSortType.entries.forEach { sort ->
                    val selected = activeSort == sort
                    FilterChip(
                        selected = selected,
                        onClick = { onSortSelected(sort) },
                        label = {
                            Text(
                                when (sort) {
                                    ChannelsSortType.DEFAULT -> "Default"
                                    ChannelsSortType.NAME_AZ -> "Name A-Z"
                                    ChannelsSortType.NAME_ZA -> "Name Z-A"
                                    ChannelsSortType.RECENTLY_ADDED -> "Recently Added"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = Gunmetal,
                            labelColor = Torve.colors.textSecondary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = Charcoal,
                            selectedBorderColor = Amber.copy(alpha = 0.3f),
                            enabled = true,
                            selected = selected,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
