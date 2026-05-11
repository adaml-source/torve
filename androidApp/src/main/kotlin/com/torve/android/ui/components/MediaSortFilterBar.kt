package com.torve.android.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.torve.android.R

/**
 * Sort/filter strip used by SeeAll-style screens and the Library tab.
 * Stateless: owner supplies current selections + change callbacks.
 */
@Composable
fun MediaSortFilterBar(
    currentSort: SortOption,
    availableSorts: List<SortOption>,
    onSortSelected: (SortOption) -> Unit,
    availableGenres: List<Pair<Int, String>>,
    selectedGenreIds: Set<Int>,
    onGenreToggled: (Int) -> Unit,
    availableYearRange: IntRange?,
    selectedYearFrom: Int?,
    selectedYearTo: Int?,
    onYearRangeChanged: (Int?, Int?) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortDropdown(
                current = currentSort,
                options = availableSorts,
                onSelected = onSortSelected,
            )
            if (availableYearRange != null) {
                YearDropdown(
                    label = stringResource(R.string.filter_from),
                    range = availableYearRange,
                    selected = selectedYearFrom,
                    onSelected = { onYearRangeChanged(it, selectedYearTo) },
                )
                YearDropdown(
                    label = stringResource(R.string.filter_to),
                    range = availableYearRange,
                    selected = selectedYearTo,
                    onSelected = { onYearRangeChanged(selectedYearFrom, it) },
                )
            }
            val anyFilterActive = selectedGenreIds.isNotEmpty() ||
                selectedYearFrom != null || selectedYearTo != null
            if (anyFilterActive) {
                AssistChip(
                    onClick = onClearFilters,
                    label = { Text(stringResource(R.string.common_clear)) },
                    leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                )
            }
        }
        if (availableGenres.isNotEmpty()) {
            Spacer(Modifier.padding(top = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                availableGenres.take(20).forEach { (id, name) ->
                    val selected = id in selectedGenreIds
                    FilterChip(
                        selected = selected,
                        onClick = { onGenreToggled(id) },
                        label = { Text(name, style = MaterialTheme.typography.labelMedium) },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
    }
}

/**
 * Sort options exposed in the dropdown. `key` is opaque to the component;
 * the caller maps it back to its own enum.
 */
data class SortOption(val key: String, val label: String)

@Composable
private fun SortDropdown(
    current: SortOption,
    options: List<SortOption>,
    onSelected: (SortOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = true },
        label = { Text(stringResource(R.string.sort_label, current.label), style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        colors = AssistChipDefaults.assistChipColors(),
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { opt ->
            DropdownMenuItem(
                text = { Text(opt.label) },
                onClick = {
                    onSelected(opt)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun YearDropdown(
    label: String,
    range: IntRange,
    selected: Int?,
    onSelected: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anyLabel = stringResource(R.string.filter_any)
    AssistChip(
        onClick = { expanded = true },
        label = { Text("$label: ${selected?.toString() ?: anyLabel}", style = MaterialTheme.typography.labelMedium) },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(anyLabel) },
            onClick = {
                onSelected(null)
                expanded = false
            },
        )
        // Newest first.
        for (year in range.last downTo range.first) {
            DropdownMenuItem(
                text = { Text(year.toString()) },
                onClick = {
                    onSelected(year)
                    expanded = false
                },
            )
        }
    }
}
