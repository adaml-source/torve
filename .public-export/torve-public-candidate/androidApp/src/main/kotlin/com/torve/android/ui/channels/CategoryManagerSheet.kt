package com.torve.android.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.IndeterminateCheckBox
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.ChannelCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerSheet(
    categories: List<ChannelCategory>,
    hiddenCategories: Set<String>,
    onToggleCategory: (String) -> Unit,
    onHideAll: () -> Unit,
    onShowAll: () -> Unit,
    onHideCountry: (String) -> Unit,
    onShowCountry: (String) -> Unit,
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
                text = stringResource(R.string.channels_manage_categories),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Snow,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.channels_select_countries),
                style = MaterialTheme.typography.bodySmall,
                color = Torve.colors.textTertiary,
            )

            Spacer(Modifier.height(12.dp))

            // Global Select All / Deselect All
            val hiddenLower = hiddenCategories.map { it.lowercase() }.toSet()
            val allHidden = categories.all { it.name.lowercase() in hiddenLower }
            val noneHidden = hiddenCategories.isEmpty()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onShowAll,
                    enabled = !noneHidden,
                ) {
                    Text(
                        "Select All",
                        color = if (noneHidden) Torve.colors.textHint else Amber,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = onHideAll,
                    enabled = !allHidden,
                ) {
                    Text(
                        "Deselect All",
                        color = if (allHidden) Torve.colors.textHint else Torve.colors.error,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Group categories by country code
            val grouped = categories.groupBy { it.countryCode ?: "Other" }
                .toSortedMap(compareBy { if (it == "Other") "zzz" else it.lowercase() })

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                grouped.forEach { (country, countryCats) ->
                    // Country header with bulk toggle
                    item(key = "country_$country") {
                        val countryHiddenCount = countryCats.count { it.name.lowercase() in hiddenLower }
                        val allCountryHidden = countryHiddenCount == countryCats.size
                        val someCountryHidden = countryHiddenCount > 0 && !allCountryHidden

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    if (allCountryHidden) onShowCountry(country)
                                    else onHideCountry(country)
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = when {
                                        allCountryHidden -> Icons.Rounded.CheckBoxOutlineBlank
                                        someCountryHidden -> Icons.Rounded.IndeterminateCheckBox
                                        else -> Icons.Rounded.CheckBox
                                    },
                                    contentDescription = null,
                                    tint = if (allCountryHidden) Torve.colors.textHint else Amber,
                                    modifier = Modifier.size(22.dp),
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Text(
                                text = country,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Amber,
                            )

                            Spacer(Modifier.width(8.dp))

                            val totalChannels = countryCats.sumOf { it.channelCount }
                            val visibleCount = countryCats.size - countryHiddenCount
                            Text(
                                text = "$visibleCount/${countryCats.size} categories · $totalChannels ch",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ash,
                            )
                        }

                        HorizontalDivider(
                            color = Steel.copy(alpha = 0.3f),
                            thickness = 0.5.dp,
                        )
                    }

                    // Individual category toggles
                    items(countryCats, key = { "cat_${country}_${it.name}" }) { category ->
                        val isHidden = category.name.lowercase() in hiddenLower
                        CategoryToggleRow(
                            name = category.name,
                            channelCount = category.channelCount,
                            isHidden = isHidden,
                            onToggle = { onToggleCategory(category.name) },
                        )
                    }

                    // Spacer between country groups
                    item(key = "spacer_$country") {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CategoryToggleRow(
    name: String,
    channelCount: Int,
    isHidden: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(
                if (isHidden) Gunmetal.copy(alpha = 0.3f) else Gunmetal,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = if (isHidden) "Hidden" else "Visible",
            tint = if (isHidden) Torve.colors.textHint else Amber,
            modifier = Modifier.size(18.dp),
        )

        Spacer(Modifier.width(10.dp))

        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (isHidden) Torve.colors.textHint else Snow,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = "$channelCount",
            style = MaterialTheme.typography.labelMedium,
            color = if (isHidden) Torve.colors.textHint else Ash,
        )
    }
}
