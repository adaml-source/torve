package com.streamvault.android.ui.settings

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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.PosterCard
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
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaRatings
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.model.RatingPillPosition
import com.streamvault.domain.model.RatingPillStyle
import com.streamvault.domain.model.RatingSource
import com.streamvault.domain.model.resolveCardStyle
import com.streamvault.domain.model.CardStyle
import com.streamvault.domain.model.defaultTorveWeights
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
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Show Torve Score on Detail", color = Snow, fontWeight = FontWeight.SemiBold)
                                Text("Displays weighted Torve Score in metadata row", color = Silver, fontSize = 12.sp)
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
                                Text("Allow Torve Score Pill on Cards", color = Snow, fontWeight = FontWeight.SemiBold)
                                Text("Enable Torve Score in provider pills", color = Silver, fontSize = 12.sp)
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
                                Text("Allow Ratings on Landscape Cards", color = Snow, fontWeight = FontWeight.SemiBold)
                                Text("Show compact rating pills on backdrop-style cards", color = Silver, fontSize = 12.sp)
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
                        Text("Torve Score Weights", color = Snow, fontWeight = FontWeight.SemiBold)
                        Text("Weights are normalized using available sources", color = Silver, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        listOf(
                            RatingSource.IMDB to "IMDb",
                            RatingSource.TMDB to "TMDB",
                            RatingSource.ROTTEN_TOMATOES to "Rotten Tomatoes",
                            RatingSource.METACRITIC to "Metacritic",
                        ).forEach { (source, label) ->
                            val current = prefs.torveWeights[source] ?: 0
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, color = Snow, fontSize = 13.sp)
                                Text("$current", color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Slider(
                                value = current.toFloat(),
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
                        TextButton(
                            onClick = { viewModel.updateRatingPrefs(prefs.copy(torveWeights = defaultTorveWeights())) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reset Torve Weights", color = Amber)
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
                        Text("Pill Position", color = Snow, fontWeight = FontWeight.SemiBold)
                        Text("Where to show rating pills on poster cards", color = Silver, fontSize = 12.sp)
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
                            Text("Max Ratings on Card", color = Snow, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${prefs.maxRatingsOnCard}",
                                color = Amber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                        Text("How many rating pills to show on poster cards", color = Silver, fontSize = 12.sp)
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

            // Preview
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Preview", color = Snow, fontWeight = FontWeight.SemiBold)
                        Text("Compact preview of inside vs outside placement", color = Silver, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        RatingsPreviewRow(prefs = prefs, baseStyle = defaultCardStyle)
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
                                        viewModel.updateRatingPrefs(prefs.copy(providerOrder = reordered))
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
                                    source.iconChar,
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
                    Text("Reset to Defaults", color = Amber)
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
            RatingPillPosition.INSIDE to "Inside Card",
            RatingPillPosition.OUTSIDE to "Outside Card",
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
                title = "Inside",
                position = RatingPillPosition.INSIDE,
                selected = prefs.pillPosition == RatingPillPosition.INSIDE,
                prefs = prefs,
                item = mockItem,
                width = previewWidth,
                baseStyle = baseStyle,
            )
            RatingPreviewCard(
                title = "Outside",
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
