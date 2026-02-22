package com.streamvault.android.ui.iptv

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.presentation.iptv.IptvFilterType
import com.streamvault.presentation.iptv.IptvSortType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    activeFilter: IptvFilterType,
    activeSort: IptvSortType,
    onFilterSelected: (IptvFilterType) -> Unit,
    onSortSelected: (IptvSortType) -> Unit,
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
                text = "Filter",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IptvFilterType.entries.forEach { filter ->
                    val selected = activeFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onFilterSelected(filter) },
                        label = {
                            Text(
                                when (filter) {
                                    IptvFilterType.ALL -> "All"
                                    IptvFilterType.HD -> "HD"
                                    IptvFilterType.FHD -> "FHD"
                                    IptvFilterType.UHD -> "4K / UHD"
                                    IptvFilterType.FAVORITES -> "Favorites"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = Obsidian,
                            containerColor = Gunmetal,
                            labelColor = StreamVault.colors.textSecondary,
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
                text = "Sort",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IptvSortType.entries.forEach { sort ->
                    val selected = activeSort == sort
                    FilterChip(
                        selected = selected,
                        onClick = { onSortSelected(sort) },
                        label = {
                            Text(
                                when (sort) {
                                    IptvSortType.DEFAULT -> "Default"
                                    IptvSortType.NAME_AZ -> "Name A-Z"
                                    IptvSortType.NAME_ZA -> "Name Z-A"
                                    IptvSortType.RECENTLY_ADDED -> "Recently Added"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = Gunmetal,
                            labelColor = StreamVault.colors.textSecondary,
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
