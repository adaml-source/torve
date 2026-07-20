package com.torve.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.WatchedOverlay
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
import com.torve.domain.model.CardAppearancePrefs
import com.torve.domain.model.CardHoverPrefs
import com.torve.domain.model.CardOrientation
import com.torve.domain.model.CardScrollAnimation
import com.torve.domain.model.CardSizePrefs
import com.torve.domain.model.CardSizePreset
import com.torve.domain.model.CardStyle
import com.torve.domain.model.CardTitlePosition
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchState
import com.torve.domain.model.WatchedIndicatorPrefs
import com.torve.domain.model.WatchedIndicatorStyle
import com.torve.domain.model.resolvedAspectRatio
import com.torve.domain.model.resolvedWidthDp
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun CardStyleSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val presets = state.cardStylePresets
    val defaultPresetId = state.globalDefaultPresetId
    var selectedPresetId by remember(defaultPresetId) {
        mutableStateOf(defaultPresetId ?: presets.firstOrNull()?.presetId)
    }
    val currentPreset = presets.firstOrNull { it.presetId == selectedPresetId } ?: presets.firstOrNull()
    val currentStyle = currentPreset?.cardStyle ?: CardStyle()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    val presetCopyTemplate = stringResource(R.string.card_style_preset_copy_template)
    val newPresetName = stringResource(R.string.card_style_new_preset)

    fun updateStyle(update: (CardStyle) -> CardStyle) {
        currentPreset?.let { preset ->
            viewModel.updateCardStylePreset(preset.presetId, update(preset.cardStyle))
        }
    }

    androidx.compose.runtime.LaunchedEffect(presets, defaultPresetId) {
        if (selectedPresetId == null || presets.none { it.presetId == selectedPresetId }) {
            selectedPresetId = defaultPresetId ?: presets.firstOrNull()?.presetId
        }
    }

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
                stringResource(R.string.card_style_title),
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
            // Preset selection & actions
            item {
                Spacer(Modifier.height(4.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.card_style_preset), style = MaterialTheme.typography.titleMedium, color = Snow)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            presets.forEach { preset ->
                                val label = if (preset.presetId == defaultPresetId) stringResource(R.string.card_style_default_suffix, preset.name) else preset.name
                                FilterChip(
                                    selected = preset.presetId == selectedPresetId,
                                    onClick = { selectedPresetId = preset.presetId },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AmberSubtle,
                                        selectedLabelColor = Amber,
                                        containerColor = Gunmetal,
                                        labelColor = Ash,
                                    ),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    currentPreset?.let { viewModel.setDefaultCardStylePreset(it.presetId) }
                                },
                                enabled = currentPreset?.presetId != null && currentPreset.presetId != defaultPresetId,
                            ) {
                                Text(stringResource(R.string.card_style_set_default), color = Amber)
                            }
                            TextButton(
                                onClick = {
                                    nameDraft = currentPreset?.name?.let { String.format(presetCopyTemplate, it) } ?: newPresetName
                                    showCreateDialog = true
                                },
                                enabled = currentPreset != null,
                            ) {
                                Text(stringResource(R.string.card_style_save_as_preset), color = Amber)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    currentPreset?.let { viewModel.duplicateCardStylePreset(it.presetId) }
                                },
                                enabled = currentPreset != null,
                            ) {
                                Text(stringResource(R.string.common_duplicate), color = Amber)
                            }
                            TextButton(
                                onClick = {
                                    nameDraft = currentPreset?.name.orEmpty()
                                    showRenameDialog = true
                                },
                                enabled = currentPreset != null,
                            ) {
                                Text(stringResource(R.string.common_rename), color = Amber)
                            }
                            TextButton(
                                onClick = { showDeleteDialog = true },
                                enabled = currentPreset != null &&
                                    currentPreset.presetId != defaultPresetId &&
                                    currentPreset.isBuiltIn.not(),
                            ) {
                                Text(stringResource(R.string.common_delete), color = if (currentPreset != null &&
                                    currentPreset.presetId != defaultPresetId &&
                                    currentPreset.isBuiltIn.not()
                                ) Amber else Silver)
                            }
                        }
                    }
                }
            }

            // Quick Presets
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.card_style_quick_presets), style = MaterialTheme.typography.titleMedium, color = Snow)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.card_style_quick_desc), style = MaterialTheme.typography.bodySmall, color = Silver)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickPresetButton(stringResource(R.string.card_style_cinema)) {
                                updateStyle {
                                    CardStyle(
                                        size = CardSizePrefs(preset = CardSizePreset.L, orientation = CardOrientation.PORTRAIT),
                                        hover = CardHoverPrefs(enabled = true, scalePercent = 110, elevationOnHover = true, borderOnHover = true),
                                        watched = WatchedIndicatorPrefs(style = WatchedIndicatorStyle.CHECKMARK_OVERLAY, dimWatched = true, dimAmount = 0.4f),
                                        appearance = CardAppearancePrefs(cornerRadiusDp = 12, showBottomGradient = true, titlePosition = CardTitlePosition.OVERLAY_BOTTOM, showYear = true),
                                        ratingPrefs = it.ratingPrefs,
                                    )
                                }
                            }
                            QuickPresetButton(stringResource(R.string.card_style_compact)) {
                                updateStyle {
                                    CardStyle(
                                        size = CardSizePrefs(preset = CardSizePreset.S, orientation = CardOrientation.PORTRAIT),
                                        hover = CardHoverPrefs(enabled = false),
                                        watched = WatchedIndicatorPrefs(style = WatchedIndicatorStyle.DOT, dimWatched = false),
                                        appearance = CardAppearancePrefs(cornerRadiusDp = 4, cardSpacingDp = 6, showBottomGradient = false, titlePosition = CardTitlePosition.BELOW, showYear = false),
                                        ratingPrefs = it.ratingPrefs,
                                    )
                                }
                            }
                            QuickPresetButton(stringResource(R.string.card_style_classic)) {
                                updateStyle {
                                    CardStyle(
                                        size = CardSizePrefs(preset = CardSizePreset.M, orientation = CardOrientation.PORTRAIT),
                                        hover = CardHoverPrefs(enabled = true, scalePercent = 115),
                                        watched = WatchedIndicatorPrefs(style = WatchedIndicatorStyle.CHECKMARK_BADGE),
                                        appearance = CardAppearancePrefs(cornerRadiusDp = 8, showBottomGradient = true, titlePosition = CardTitlePosition.BELOW, showYear = true),
                                        ratingPrefs = it.ratingPrefs,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Charcoal),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.card_style_live_preview), color = Snow, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        CardStyleLivePreview(style = currentStyle)
                    }
                }
            }


            item {
                Spacer(Modifier.height(4.dp))
                CardSizeSection(currentStyle.size) { updateStyle { style -> style.copy(size = it) } }
            }

            item { HorizontalDivider(color = Steel.copy(alpha = 0.2f)) }

            // ─── Hover / Focus Zoom ───
            item {
                CardHoverSection(currentStyle.hover) { updateStyle { style -> style.copy(hover = it) } }
            }

            item { HorizontalDivider(color = Steel.copy(alpha = 0.2f)) }

            // ─── Watched Indicator ───
            item {
                WatchedIndicatorSection(currentStyle.watched) { updateStyle { style -> style.copy(watched = it) } }
            }

            item { HorizontalDivider(color = Steel.copy(alpha = 0.2f)) }

            // ─── Appearance ───
            item {
                AppearanceSection(currentStyle.appearance) { updateStyle { style -> style.copy(appearance = it) } }
            }

            // ─── Reset ───
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { updateStyle { CardStyle() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.card_style_reset_all), color = Amber)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showRenameDialog && currentPreset != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.card_style_rename_title), color = Snow) },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber,
                        focusedLabelColor = Amber,
                        unfocusedBorderColor = Steel,
                        unfocusedLabelColor = Silver,
                        cursorColor = Amber,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = nameDraft.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.renameCardStylePreset(currentPreset.presetId, trimmed)
                        }
                        showRenameDialog = false
                    },
                ) { Text(stringResource(R.string.common_save), color = Amber) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.common_cancel), color = Silver) }
            },
            containerColor = Charcoal,
        )
    }

    if (showCreateDialog && currentPreset != null) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.card_style_save_title), color = Snow) },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber,
                        focusedLabelColor = Amber,
                        unfocusedBorderColor = Steel,
                        unfocusedLabelColor = Silver,
                        cursorColor = Amber,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = nameDraft.trim()
                        if (trimmed.isNotEmpty()) {
                            val newId = viewModel.createCardStylePreset(trimmed, currentPreset.cardStyle.copy())
                            selectedPresetId = newId
                        }
                        showCreateDialog = false
                    },
                ) { Text(stringResource(R.string.common_save), color = Amber) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.common_cancel), color = Silver) }
            },
            containerColor = Charcoal,
        )
    }

    if (showDeleteDialog && currentPreset != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.card_style_delete_title), color = Snow) },
            text = {
                Text(
                    stringResource(R.string.card_style_delete_desc, currentPreset.name),
                    color = Silver,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCardStylePreset(currentPreset.presetId)
                        showDeleteDialog = false
                    },
                ) { Text(stringResource(R.string.common_delete), color = Amber) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel), color = Silver) }
            },
            containerColor = Charcoal,
        )
    }
}

@Composable
private fun CardStyleLivePreview(style: CardStyle) {
    val previewTitle = stringResource(R.string.card_style_preview_title)
    val previewItem = MediaItem(
        id = "settings_preview",
        type = MediaType.MOVIE,
        title = previewTitle,
        year = 2026,
        rating = 7.9,
        ratings = MediaRatings(
            imdbScore = 7.9f,
            tmdbScore = 7.4f,
            rottenTomatoesScore = 83,
        ),
    )
    CompositionLocalProvider(LocalCardStyle provides style) {
        PosterCard(
            item = previewItem,
            onClick = {},
            sizeOverride = CardSize.MEDIUM,
            cardStyle = style,
        )
    }
}
// ─── Card Size Section ───

@Composable
private fun CardSizeSection(
    prefs: CardSizePrefs,
    onUpdate: (CardSizePrefs) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.card_style_card_size), style = MaterialTheme.typography.titleMedium, color = Snow)
            Spacer(Modifier.height(12.dp))

            // Orientation picker
            Text(stringResource(R.string.card_style_orientation), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardOrientation.entries.forEach { orientation ->
                    val selected = prefs.orientation == orientation
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdate(prefs.copy(orientation = orientation)) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val (w, h) = when (orientation) {
                                    CardOrientation.PORTRAIT -> 12.dp to 18.dp
                                    CardOrientation.LANDSCAPE -> 20.dp to 11.dp
                                    CardOrientation.SQUARE -> 14.dp to 14.dp
                                }
                                Box(
                                    Modifier
                                        .size(w, h)
                                        .border(
                                            1.dp,
                                            if (selected) Amber else Ash,
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                                Text(
                                    orientation.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontSize = 12.sp,
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = Gunmetal,
                            labelColor = Ash,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Size preset picker
            Text(stringResource(R.string.card_style_size), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                CardSizePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = prefs.preset == preset,
                        onClick = { onUpdate(prefs.copy(preset = preset)) },
                        label = { Text(preset.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = Gunmetal,
                            labelColor = Ash,
                        ),
                    )
                }
            }

            // Custom slider
            AnimatedVisibility(visible = prefs.preset == CardSizePreset.CUSTOM) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.card_style_card_width, prefs.customWidthDp), color = Silver, fontSize = 12.sp)
                    Slider(
                        value = prefs.customWidthDp.toFloat(),
                        onValueChange = { onUpdate(prefs.copy(customWidthDp = it.roundToInt())) },
                        valueRange = 60f..300f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Amber,
                            activeTrackColor = Amber,
                            inactiveTrackColor = Graphite,
                        ),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.card_style_custom_width_min), color = Ash, fontSize = 10.sp)
                        Text(stringResource(R.string.card_style_custom_width_max), color = Ash, fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Live preview
            Text(stringResource(R.string.card_style_live_preview), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Obsidian, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val widthDp = prefs.resolvedWidthDp()
                val ratio = prefs.resolvedAspectRatio()
                Box(
                    Modifier
                        .width(widthDp.dp)
                        .aspectRatio(ratio)
                        .background(Gunmetal, RoundedCornerShape(8.dp))
                        .border(1.dp, Steel, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val heightVal = (widthDp / ratio).roundToInt()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${widthDp} \u00D7 $heightVal",
                            color = Amber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            prefs.orientation.name.lowercase(),
                            color = Silver,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

// ─── Hover / Focus Zoom Section ───

@Composable
private fun CardHoverSection(
    prefs: CardHoverPrefs,
    onUpdate: (CardHoverPrefs) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.card_style_hover_zoom), color = Snow, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.card_style_hover_zoom_desc), color = Silver, fontSize = 12.sp)
                }
                Switch(
                    checked = prefs.enabled,
                    onCheckedChange = { onUpdate(prefs.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Amber,
                        checkedTrackColor = AmberSubtle,
                        uncheckedThumbColor = Steel,
                        uncheckedTrackColor = Gunmetal,
                    ),
                )
            }

            AnimatedVisibility(visible = prefs.enabled) {
                Column {
                    Spacer(Modifier.height(16.dp))

                    // Scale slider
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.card_style_zoom_amount), color = Silver, fontSize = 13.sp)
                        Text("${prefs.scalePercent}%", color = Amber, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = prefs.scalePercent.toFloat(),
                        onValueChange = { onUpdate(prefs.copy(scalePercent = it.roundToInt())) },
                        valueRange = 100f..150f,
                        steps = 9,
                        colors = SliderDefaults.colors(
                            thumbColor = Amber,
                            activeTrackColor = Amber,
                            inactiveTrackColor = Graphite,
                        ),
                    )

                    Spacer(Modifier.height(8.dp))

                    // Animation speed
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.card_style_animation_speed), color = Silver, fontSize = 13.sp)
                        Text("${prefs.animationDurationMs}ms", color = Amber, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = prefs.animationDurationMs.toFloat(),
                        onValueChange = { onUpdate(prefs.copy(animationDurationMs = it.roundToInt())) },
                        valueRange = 50f..500f,
                        colors = SliderDefaults.colors(
                            thumbColor = Amber,
                            activeTrackColor = Amber,
                            inactiveTrackColor = Graphite,
                        ),
                    )

                    Spacer(Modifier.height(8.dp))

                    ToggleRow(stringResource(R.string.card_style_elevation_shadow), prefs.elevationOnHover) {
                        onUpdate(prefs.copy(elevationOnHover = it))
                    }
                    ToggleRow(stringResource(R.string.card_style_accent_border), prefs.borderOnHover) {
                        onUpdate(prefs.copy(borderOnHover = it))
                    }
                    ToggleRow(stringResource(R.string.card_style_dim_other_cards), prefs.dimOtherCards) {
                        onUpdate(prefs.copy(dimOtherCards = it))
                    }

                    // Live hover preview
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.card_style_preview_tap), color = Silver, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    HoverPreview(prefs)
                }
            }
        }
    }
}

@Composable
private fun HoverPreview(prefs: CardHoverPrefs) {
    var isHovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isHovered) prefs.scalePercent / 100f else 1f,
        animationSpec = tween(prefs.animationDurationMs),
        label = "hover_scale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isHovered && prefs.elevationOnHover) 12.dp else 0.dp,
        animationSpec = tween(prefs.animationDurationMs),
        label = "hover_elev",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(Obsidian, RoundedCornerShape(8.dp))
            .padding(vertical = 20.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val isTarget = index == 1
            val cardScale = if (isTarget) scale else 1f
            val cardAlpha = if (!isTarget && isHovered && prefs.dimOtherCards) 0.4f else 1f

            Box(
                Modifier
                    .size(60.dp, 90.dp)
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                        alpha = cardAlpha
                        if (isTarget && isHovered && prefs.elevationOnHover) {
                            shadowElevation = elevation.toPx()
                        }
                    }
                    .then(
                        if (isTarget && isHovered && prefs.borderOnHover) {
                            Modifier.border(2.dp, Amber, RoundedCornerShape(6.dp))
                        } else Modifier,
                    )
                    .background(
                        if (isTarget) Color(0xFF3A3A5E) else Gunmetal,
                        RoundedCornerShape(6.dp),
                    )
                    .then(
                        if (isTarget) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isHovered = true
                                        tryAwaitRelease()
                                        isHovered = false
                                    },
                                )
                            }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isTarget) {
                    Text(stringResource(R.string.card_style_tap), color = Amber, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Watched Indicator Section ───

@Composable
private fun WatchedIndicatorSection(
    prefs: WatchedIndicatorPrefs,
    onUpdate: (WatchedIndicatorPrefs) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.card_style_watched_indicator), color = Snow, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.card_style_watched_desc), color = Silver, fontSize = 12.sp)
                }
                Switch(
                    checked = prefs.enabled,
                    onCheckedChange = { onUpdate(prefs.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Amber,
                        checkedTrackColor = AmberSubtle,
                        uncheckedThumbColor = Steel,
                        uncheckedTrackColor = Gunmetal,
                    ),
                )
            }

            AnimatedVisibility(visible = prefs.enabled) {
                Column {
                    Spacer(Modifier.height(16.dp))

                    Text(stringResource(R.string.card_style_indicator_style), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    // Style picker — scrollable row with mini previews
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WatchedIndicatorStyle.entries.forEach { style ->
                            val selected = prefs.style == style
                            Column(
                                Modifier
                                    .width(70.dp)
                                    .background(
                                        if (selected) AmberSubtle else Gunmetal,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .then(
                                        if (selected) Modifier.border(1.5.dp, Amber, RoundedCornerShape(8.dp))
                                        else Modifier,
                                    )
                                    .clickable { onUpdate(prefs.copy(style = style)) }
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .size(40.dp, 56.dp)
                                        .background(Color(0xFF3A3A5E), RoundedCornerShape(4.dp)),
                                ) {
                                    WatchedOverlay(
                                        watchState = WatchState(
                                            isStarted = true,
                                            isCompleted = true,
                                        ),
                                        prefs = prefs.copy(style = style, dimWatched = false),
                                        cornerRadiusDp = 4,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    style.label,
                                    color = if (selected) Amber else Silver,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    ToggleRow(stringResource(R.string.card_style_dim_watched_posters), prefs.dimWatched) {
                        onUpdate(prefs.copy(dimWatched = it))
                    }
                    AnimatedVisibility(visible = prefs.dimWatched) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.card_style_dim_amount), color = Silver, fontSize = 12.sp)
                                Text("${(prefs.dimAmount * 100).roundToInt()}%", color = Amber, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = prefs.dimAmount,
                                onValueChange = { onUpdate(prefs.copy(dimAmount = it)) },
                                valueRange = 0.1f..0.9f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Amber,
                                    activeTrackColor = Amber,
                                    inactiveTrackColor = Graphite,
                                ),
                            )
                        }
                    }

                    ToggleRow(stringResource(R.string.card_style_progress_bar_partial), prefs.progressBarForPartial) {
                        onUpdate(prefs.copy(progressBarForPartial = it))
                    }
                    ToggleRow(stringResource(R.string.card_style_rewatch_badge), prefs.rewatchBadge) {
                        onUpdate(prefs.copy(rewatchBadge = it))
                    }
                }
            }
        }
    }
}

// ─── Appearance Section ───

@Composable
private fun AppearanceSection(
    prefs: CardAppearancePrefs,
    onUpdate: (CardAppearancePrefs) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Charcoal),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.card_style_appearance), style = MaterialTheme.typography.titleMedium, color = Snow)
            Spacer(Modifier.height(12.dp))

            // Corner radius
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.card_style_corner_radius), color = Silver, fontSize = 13.sp)
                Text("${prefs.cornerRadiusDp}dp", color = Amber, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = prefs.cornerRadiusDp.toFloat(),
                onValueChange = { onUpdate(prefs.copy(cornerRadiusDp = it.roundToInt())) },
                valueRange = 0f..24f,
                steps = 11,
                colors = SliderDefaults.colors(
                    thumbColor = Amber,
                    activeTrackColor = Amber,
                    inactiveTrackColor = Graphite,
                ),
            )

            Spacer(Modifier.height(8.dp))

            // Card spacing
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.card_style_card_spacing), color = Silver, fontSize = 13.sp)
                Text("${prefs.cardSpacingDp}dp", color = Amber, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = prefs.cardSpacingDp.toFloat(),
                onValueChange = { onUpdate(prefs.copy(cardSpacingDp = it.roundToInt())) },
                valueRange = 4f..24f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = Amber,
                    activeTrackColor = Amber,
                    inactiveTrackColor = Graphite,
                ),
            )

            Spacer(Modifier.height(8.dp))

            // Title position
            Text(stringResource(R.string.card_style_title_position), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitlePosition.entries.forEach { pos ->
                    FilterChip(
                        selected = prefs.titlePosition == pos,
                        onClick = { onUpdate(prefs.copy(titlePosition = pos)) },
                        label = {
                            Text(
                                when (pos) {
                                    CardTitlePosition.BELOW -> stringResource(R.string.card_style_title_pos_below)
                                    CardTitlePosition.OVERLAY_BOTTOM -> stringResource(R.string.card_style_title_pos_overlay)
                                    CardTitlePosition.HIDDEN -> stringResource(R.string.card_style_title_pos_hidden)
                                },
                                fontSize = 12.sp,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = Gunmetal,
                            labelColor = Ash,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Scroll animation
            Text(stringResource(R.string.card_style_scroll_animation), color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                CardScrollAnimation.entries.forEach { anim ->
                    FilterChip(
                        selected = prefs.scrollAnimation == anim,
                        onClick = { onUpdate(prefs.copy(scrollAnimation = anim)) },
                        label = {
                            Text(
                                when (anim) {
                                    CardScrollAnimation.NONE -> stringResource(R.string.card_style_scroll_none)
                                    CardScrollAnimation.FADE_IN -> stringResource(R.string.card_style_scroll_fade_in)
                                    CardScrollAnimation.SLIDE_UP -> stringResource(R.string.card_style_scroll_slide_up)
                                    CardScrollAnimation.SCALE_IN -> stringResource(R.string.card_style_scroll_scale_in)
                                },
                                fontSize = 12.sp,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberSubtle,
                            selectedLabelColor = Amber,
                            containerColor = Gunmetal,
                            labelColor = Ash,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Toggle options
            ToggleRow(stringResource(R.string.card_style_show_year), prefs.showYear) { onUpdate(prefs.copy(showYear = it)) }
            ToggleRow(stringResource(R.string.card_style_bottom_gradient), prefs.showBottomGradient) { onUpdate(prefs.copy(showBottomGradient = it)) }
            ToggleRow(stringResource(R.string.card_style_card_border), prefs.showBorder) { onUpdate(prefs.copy(showBorder = it)) }
            ToggleRow(stringResource(R.string.card_style_genre_tags), prefs.showGenreTags) { onUpdate(prefs.copy(showGenreTags = it)) }
            ToggleRow(stringResource(R.string.card_style_type_badge), prefs.showTypeBadge) { onUpdate(prefs.copy(showTypeBadge = it)) }
            ToggleRow(stringResource(R.string.card_style_runtime), prefs.showRuntime) { onUpdate(prefs.copy(showRuntime = it)) }
        }
    }
}

// ─── Shared Components ───

@Composable
private fun QuickPresetButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .background(Gunmetal, RoundedCornerShape(8.dp)),
    ) {
        Text(label, color = Amber, fontSize = 12.sp)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Snow, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Amber,
                checkedTrackColor = AmberSubtle,
                uncheckedThumbColor = Steel,
                uncheckedTrackColor = Gunmetal,
            ),
        )
    }
}



