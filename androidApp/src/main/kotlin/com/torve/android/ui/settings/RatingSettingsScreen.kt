package com.torve.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.getRatingSourceColor
import com.torve.android.R
import com.torve.android.ui.components.getRatingSourceExample
import com.torve.android.ui.components.ratingSourceIconRes
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.AmberSubtle
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.RatingPillPosition
import com.torve.domain.model.RatingPillStyle
import com.torve.domain.model.RatingSource
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.model.CardStyle
import com.torve.domain.model.defaultTorveWeights
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun RatingSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val prefs = state.ratingPrefs
    val defaultCardStyle = resolveCardStyle(
        presets = state.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = state.globalDefaultPresetId,
    )

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
                stringResource(R.string.ratings_title),
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
                                Text(stringResource(R.string.ratings_show_detail), color = Snow, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.ratings_show_detail_desc), color = Silver, fontSize = 12.sp)
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
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ratings_torve_detail), color = Snow, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.ratings_torve_detail_desc), color = Silver, fontSize = 12.sp)
                            }
                            Switch(
                                checked = prefs.showTorveScoreOnDetailPage,
                                onCheckedChange = { viewModel.updateRatingPrefs(prefs.copy(showTorveScoreOnDetailPage = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Amber, checkedTrackColor = AmberSubtle,
                                    uncheckedThumbColor = Steel, uncheckedTrackColor = Gunmetal,
                                ),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ratings_torve_cards), color = Snow, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.ratings_torve_cards_desc), color = Silver, fontSize = 12.sp)
                            }
                            Switch(
                                checked = prefs.showTorveScoreOnCards,
                                onCheckedChange = { viewModel.updateRatingPrefs(prefs.copy(showTorveScoreOnCards = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Amber, checkedTrackColor = AmberSubtle,
                                    uncheckedThumbColor = Steel, uncheckedTrackColor = Gunmetal,
                                ),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ratings_landscape), color = Snow, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.ratings_landscape_desc), color = Silver, fontSize = 12.sp)
                            }
                            Switch(
                                checked = prefs.allowRatingsOnLandscapeCards,
                                onCheckedChange = {
                                    viewModel.updateRatingPrefs(
                                        prefs.copy(allowRatingsOnLandscapeCards = it),
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Amber, checkedTrackColor = AmberSubtle,
                                    uncheckedThumbColor = Steel, uncheckedTrackColor = Gunmetal,
                                ),
                            )
                        }
                    }
                }
            }

            // Torve score weights
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ratings_torve_weights), color = Snow, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ratings_weights_desc), color = Silver, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        prefs.torveWeights.entries.forEach { (source, weight) ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(source.displayName, color = Snow, fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$weight", color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    IconButton(
                                        onClick = {
                                            viewModel.updateRatingPrefs(
                                                prefs.copy(torveWeights = prefs.torveWeights - source),
                                            )
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = "Remove ${source.displayName}",
                                            tint = Steel,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                            Slider(
                                value = weight.toFloat(),
                                onValueChange = { value ->
                                    viewModel.updateRatingPrefs(
                                        prefs.copy(torveWeights = prefs.torveWeights + (source to value.roundToInt())),
                                    )
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Amber,
                                    activeTrackColor = Amber,
                                    inactiveTrackColor = Graphite,
                                ),
                            )
                        }

                        // Add Provider button + dropdown
                        val availableSources = RatingSource.entries
                            .filter { it != RatingSource.TORVE && it !in prefs.torveWeights }
                        if (availableSources.isNotEmpty()) {
                            var addMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { addMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.ratings_add_provider), color = Amber)
                                }
                                DropdownMenu(
                                    expanded = addMenuExpanded,
                                    onDismissRequest = { addMenuExpanded = false },
                                ) {
                                    availableSources.forEach { source ->
                                        DropdownMenuItem(
                                            text = { Text(source.displayName) },
                                            onClick = {
                                                viewModel.updateRatingPrefs(
                                                    prefs.copy(torveWeights = prefs.torveWeights + (source to 0)),
                                                )
                                                addMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = { viewModel.updateRatingPrefs(prefs.copy(torveWeights = defaultTorveWeights())) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ratings_reset_weights), color = Amber)
                        }
                    }
                }
            }

            // Pill position picker
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ratings_pill_position), color = Snow, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ratings_pill_position_desc), color = Silver, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        PillPositionPicker(
                            selected = prefs.pillPosition,
                            onSelect = { viewModel.updateRatingPrefs(prefs.copy(pillPosition = it)) },
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
                            Text(stringResource(R.string.ratings_max_on_card), color = Snow, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${prefs.maxRatingsOnCard}",
                                color = Amber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                        Text(stringResource(R.string.ratings_max_on_card_desc), color = Silver, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Slider(
                            value = prefs.maxRatingsOnCard.toFloat(),
                            onValueChange = {
                                viewModel.updateRatingPrefs(prefs.copy(maxRatingsOnCard = it.roundToInt()))
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
                        Text(stringResource(R.string.ratings_pill_style), color = Snow, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RatingPillStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = prefs.pillStyle == style,
                                    onClick = { viewModel.updateRatingPrefs(prefs.copy(pillStyle = style)) },
                                    label = {
                                        Text(when (style) {
                                            RatingPillStyle.ICON -> stringResource(R.string.ratings_with_icon)
                                            RatingPillStyle.LETTER -> stringResource(R.string.ratings_with_letter)
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

            // Preview
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ratings_preview), color = Snow, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ratings_preview_desc), color = Silver, fontSize = 12.sp)
                    }
                }
            }

            // Per-source section header
            item {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ratings_sources), style = MaterialTheme.typography.labelLarge, color = Ash,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                Text(
                    stringResource(R.string.ratings_sources_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // Per-source toggles with reorder arrows
            val sortedSources = prefs.providerOrder
            itemsIndexed(sortedSources, key = { _, source -> source.name }) { index, source ->
                val enabled = prefs.enabledProviders.contains(source)
                val sourceColor = getRatingSourceColor(source)
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (enabled) Charcoal else Charcoal.copy(alpha = 0.5f),
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
                                        viewModel.updateRatingPrefs(prefs.copy(providerOrder = reordered))
                                    }
                                },
                                enabled = index > 0,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.home_move_up),
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
                                        viewModel.updateRatingPrefs(prefs.copy(providerOrder = reordered))
                                    }
                                },
                                enabled = index < sortedSources.size - 1,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.home_move_down),
                                    tint = if (index < sortedSources.size - 1) Amber else Steel,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        // Source icon
                        // Use a "fresh" placeholder for settings display
                        val settingsRatings = MediaRatings(rottenTomatoesScore = 75, rtAudienceScore = 75)
                        val iconRes = ratingSourceIconRes(source, settingsRatings)
                        if (iconRes != null) {
                            Image(
                                painter = painterResource(id = iconRes),
                                contentDescription = source.displayName,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            Surface(
                                Modifier.size(28.dp),
                                shape = RoundedCornerShape(6.dp),
                                color = sourceColor.copy(alpha = 0.2f),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        source.iconChar,
                                        fontSize = 14.sp,
                                        color = sourceColor,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        // Name + example
                        Column(Modifier.weight(1f)) {
                            Text(
                                source.displayName,
                                color = if (enabled) Snow else Ash,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                getRatingSourceExample(source),
                                color = if (enabled) Silver else Steel,
                                fontSize = 11.sp,
                            )
                        }

                        // Toggle
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled ->
                                val updated = if (enabled) {
                                    if (prefs.enabledProviders.contains(source)) prefs.enabledProviders
                                    else prefs.enabledProviders + source
                                } else {
                                    prefs.enabledProviders.filterNot { it == source }
                                }
                                viewModel.updateRatingPrefs(prefs.copy(enabledProviders = updated))
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
                    Text(stringResource(R.string.ratings_reset_defaults), color = Amber)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PillPositionPicker(
    selected: RatingPillPosition,
    onSelect: (RatingPillPosition) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            RatingPillPosition.INSIDE to stringResource(R.string.ratings_inside_card),
            RatingPillPosition.OUTSIDE to stringResource(R.string.ratings_outside_card),
        ).forEach { (position, label) ->
            FilterChip(
                selected = selected == position,
                onClick = { onSelect(position) },
                label = { Text(label) },
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

@Composable
private fun RatingsPreviewRow(prefs: RatingDisplayPrefs, baseStyle: CardStyle) {
    val mockRatings = MediaRatings(
        imdbScore = 7.8f,
        rottenTomatoesScore = 82,
        rtAudienceScore = 91,
        tmdbScore = 7.5f,
        metacriticScore = 74,
        letterboxdScore = 3.9f,
        traktScore = 86f,
        mdblistScore = 78f,
        malScore = 8.2f,
    )
    val mockItem = MediaItem(
        id = "ratings_preview",
        type = MediaType.MOVIE,
        title = "Preview",
        posterUrl = null,
        ratings = mockRatings,
        rating = 7.8,
    )

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 12.dp
        val maxCardWidth = 220.dp
        val available = (maxWidth - gap) / 2f
        val previewWidth = if (available < maxCardWidth) available else maxCardWidth

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            RatingPreviewCard(
                title = stringResource(R.string.ratings_inside),
                position = RatingPillPosition.INSIDE,
                selected = prefs.pillPosition == RatingPillPosition.INSIDE,
                prefs = prefs,
                item = mockItem,
                width = previewWidth,
                baseStyle = baseStyle,
            )
            RatingPreviewCard(
                title = stringResource(R.string.ratings_outside),
                position = RatingPillPosition.OUTSIDE,
                selected = prefs.pillPosition == RatingPillPosition.OUTSIDE,
                prefs = prefs,
                item = mockItem,
                width = previewWidth,
                baseStyle = baseStyle,
            )
        }
    }
}

@Composable
private fun RatingPreviewCard(
    title: String,
    position: RatingPillPosition,
    selected: Boolean,
    prefs: RatingDisplayPrefs,
    item: MediaItem,
    width: androidx.compose.ui.unit.Dp,
    baseStyle: CardStyle,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Gunmetal),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Amber) else null,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = if (selected) Snow else Silver,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val cardStyle = baseStyle.copy(ratingPrefs = prefs.copy(pillPosition = position))
            PosterCard(
                item = item,
                onClick = {},
                showTitle = false,
                sizeOverride = CardSize.MEDIUM,
                modifier = Modifier.width(width),
                cardStyle = cardStyle,
            )
        }
    }
}
