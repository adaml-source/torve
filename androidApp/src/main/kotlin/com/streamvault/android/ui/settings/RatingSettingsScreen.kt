package com.streamvault.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.components.getRatingSourceColor
import com.streamvault.android.ui.components.getRatingSourceExample
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Ash
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Graphite
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.domain.model.RatingDisplayPrefs
import com.streamvault.domain.model.RatingPillPlacement
import com.streamvault.domain.model.RatingPillStyle
import com.streamvault.domain.model.RatingSource
import com.streamvault.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun RatingSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val prefs = state.ratingPrefs

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Text(
                "Ratings",
                style = MaterialTheme.typography.titleLarge,
                color = Snow,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Master toggles
            item {
                Spacer(Modifier.height(4.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Show on Cards", color = Snow, fontWeight = FontWeight.SemiBold)
                                Text("Display rating pills on poster cards", color = Silver, fontSize = 12.sp)
                            }
                            Switch(
                                checked = prefs.showRatingsOnCards,
                                onCheckedChange = { viewModel.updateRatingPrefs(prefs.copy(showRatingsOnCards = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Amber, checkedTrackColor = AmberSubtle,
                                    uncheckedThumbColor = Steel, uncheckedTrackColor = Gunmetal,
                                ),
                            )
                        }

                        HorizontalDivider(color = Steel.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Show on Detail Page", color = Snow, fontWeight = FontWeight.SemiBold)
                                Text("Display ratings on movie/show detail screen", color = Silver, fontSize = 12.sp)
                            }
                            Switch(
                                checked = prefs.showRatingsOnDetailPage,
                                onCheckedChange = { viewModel.updateRatingPrefs(prefs.copy(showRatingsOnDetailPage = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Amber, checkedTrackColor = AmberSubtle,
                                    uncheckedThumbColor = Steel, uncheckedTrackColor = Gunmetal,
                                ),
                            )
                        }
                    }
                }
            }

            // Pill placement picker
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pill Position", color = Snow, fontWeight = FontWeight.SemiBold)
                        Text("Where to show rating pills on poster cards", color = Silver, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        PlacementPicker(
                            selected = prefs.pillPlacement,
                            onSelect = { viewModel.updateRatingPrefs(prefs.copy(pillPlacement = it)) },
                        )
                    }
                }
            }

            // Max pills on card (slider 1-9)
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Max Ratings on Card", color = Snow, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${prefs.maxPillsOnCard}",
                                color = Amber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                        Text("How many rating pills to show on poster cards", color = Silver, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Slider(
                            value = prefs.maxPillsOnCard.toFloat(),
                            onValueChange = {
                                viewModel.updateRatingPrefs(prefs.copy(maxPillsOnCard = it.roundToInt()))
                            },
                            valueRange = 1f..9f,
                            steps = 7,
                            colors = SliderDefaults.colors(
                                thumbColor = Amber,
                                activeTrackColor = Amber,
                                inactiveTrackColor = Graphite,
                            ),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("1", color = Silver, fontSize = 10.sp)
                            Text("9", color = Silver, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Pill style
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Pill Style", color = Snow, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RatingPillStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = prefs.pillStyle == style,
                                    onClick = { viewModel.updateRatingPrefs(prefs.copy(pillStyle = style)) },
                                    label = {
                                        Text(when (style) {
                                            RatingPillStyle.COMPACT -> "Compact"
                                            RatingPillStyle.MINIMAL -> "Minimal"
                                            RatingPillStyle.DETAILED -> "Detailed"
                                        })
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Amber,
                                        selectedLabelColor = Obsidian,
                                        containerColor = Gunmetal,
                                        labelColor = Snow,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            // Per-source section header
            item {
                Spacer(Modifier.height(4.dp))
                Text("Rating Sources", style = MaterialTheme.typography.labelLarge, color = Ash,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                Text(
                    "Enable sources and reorder with arrows. Higher sources show first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // Per-source toggles with reorder arrows
            val sortedSources = prefs.sources.sortedBy { it.order }
            itemsIndexed(sortedSources, key = { _, cfg -> cfg.source.name }) { index, cfg ->
                val sourceColor = getRatingSourceColor(cfg.source)
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (cfg.enabled) Charcoal else Charcoal.copy(alpha = 0.5f),
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Reorder arrows
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val reordered = sortedSources.toMutableList()
                                        val item = reordered.removeAt(index)
                                        reordered.add(index - 1, item)
                                        val updated = reordered.mapIndexed { i, c -> c.copy(order = i) }
                                        viewModel.updateRatingPrefs(prefs.copy(sources = updated))
                                    }
                                },
                                enabled = index > 0,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = "Move up",
                                    tint = if (index > 0) Amber else Steel,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (index < sortedSources.size - 1) {
                                        val reordered = sortedSources.toMutableList()
                                        val item = reordered.removeAt(index)
                                        reordered.add(index + 1, item)
                                        val updated = reordered.mapIndexed { i, c -> c.copy(order = i) }
                                        viewModel.updateRatingPrefs(prefs.copy(sources = updated))
                                    }
                                },
                                enabled = index < sortedSources.size - 1,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Move down",
                                    tint = if (index < sortedSources.size - 1) Amber else Steel,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        // Source color icon
                        Surface(
                            Modifier.size(28.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = sourceColor.copy(alpha = 0.2f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    cfg.source.iconChar,
                                    fontSize = 14.sp,
                                    color = sourceColor,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        // Name + example
                        Column(Modifier.weight(1f)) {
                            Text(
                                cfg.source.displayName,
                                color = if (cfg.enabled) Snow else Ash,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                getRatingSourceExample(cfg.source),
                                color = if (cfg.enabled) Silver else Steel,
                                fontSize = 11.sp,
                            )
                        }

                        // Toggle
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = { enabled ->
                                val updated = prefs.sources.map {
                                    if (it.source == cfg.source) it.copy(enabled = enabled) else it
                                }
                                viewModel.updateRatingPrefs(prefs.copy(sources = updated))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Amber, checkedTrackColor = AmberSubtle,
                                uncheckedThumbColor = Steel, uncheckedTrackColor = Gunmetal,
                            ),
                        )
                    }
                }
            }

            // Reset button
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { viewModel.updateRatingPrefs(RatingDisplayPrefs()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset to Defaults", color = Amber)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PlacementPicker(
    selected: RatingPillPlacement,
    onSelect: (RatingPillPlacement) -> Unit,
) {
    // Visual poster card representation with clickable placement zones
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Inside placements — mini poster card mock
        Card(
            Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = Gunmetal),
        ) {
            Column(Modifier.padding(8.dp)) {
                Text("Inside Card", color = Silver, fontSize = 10.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Graphite),
                ) {
                    // Top-left
                    PlacementDot(
                        label = "TL",
                        isSelected = selected == RatingPillPlacement.INSIDE_TOP_START,
                        onClick = { onSelect(RatingPillPlacement.INSIDE_TOP_START) },
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    )
                    // Top-right
                    PlacementDot(
                        label = "TR",
                        isSelected = selected == RatingPillPlacement.INSIDE_TOP_END,
                        onClick = { onSelect(RatingPillPlacement.INSIDE_TOP_END) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                    // Bottom-left
                    PlacementDot(
                        label = "BL",
                        isSelected = selected == RatingPillPlacement.INSIDE_BOTTOM_START,
                        onClick = { onSelect(RatingPillPlacement.INSIDE_BOTTOM_START) },
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                    // Bottom-right
                    PlacementDot(
                        label = "BR",
                        isSelected = selected == RatingPillPlacement.INSIDE_BOTTOM_END,
                        onClick = { onSelect(RatingPillPlacement.INSIDE_BOTTOM_END) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    )
                }
            }
        }

        // Outside placements
        Card(
            Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = Gunmetal),
        ) {
            Column(Modifier.padding(8.dp)) {
                Text("Outside Card", color = Silver, fontSize = 10.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Outside top
                    PlacementDot(
                        label = "Top",
                        isSelected = selected == RatingPillPlacement.OUTSIDE_TOP,
                        onClick = { onSelect(RatingPillPlacement.OUTSIDE_TOP) },
                        modifier = Modifier,
                        wide = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Graphite),
                    )
                    Spacer(Modifier.height(4.dp))
                    // Outside bottom
                    PlacementDot(
                        label = "Bottom",
                        isSelected = selected == RatingPillPlacement.OUTSIDE_BOTTOM,
                        onClick = { onSelect(RatingPillPlacement.OUTSIDE_BOTTOM) },
                        modifier = Modifier,
                        wide = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlacementDot(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val bg = if (isSelected) Amber else Gunmetal
    val textColor = if (isSelected) Obsidian else Silver
    val borderMod = if (isSelected) {
        Modifier.border(1.5.dp, Amber, if (wide) RoundedCornerShape(4.dp) else CircleShape)
    } else {
        Modifier.border(1.dp, Steel.copy(alpha = 0.5f), if (wide) RoundedCornerShape(4.dp) else CircleShape)
    }

    Box(
        modifier = modifier
            .then(if (wide) Modifier.fillMaxWidth().height(22.dp) else Modifier.size(26.dp))
            .clip(if (wide) RoundedCornerShape(4.dp) else CircleShape)
            .background(bg)
            .then(borderMod)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 8.sp,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
