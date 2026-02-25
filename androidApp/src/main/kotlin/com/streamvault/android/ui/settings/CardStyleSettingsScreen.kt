package com.streamvault.android.ui.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.components.WatchedOverlay
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
import com.streamvault.domain.model.CardAppearancePrefs
import com.streamvault.domain.model.CardHoverPrefs
import com.streamvault.domain.model.CardOrientation
import com.streamvault.domain.model.CardPrefs
import com.streamvault.domain.model.CardScrollAnimation
import com.streamvault.domain.model.CardSizePrefs
import com.streamvault.domain.model.CardSizePreset
import com.streamvault.domain.model.CardTitlePosition
import com.streamvault.domain.model.WatchState
import com.streamvault.domain.model.WatchedIndicatorPrefs
import com.streamvault.domain.model.WatchedIndicatorStyle
import com.streamvault.domain.model.resolvedAspectRatio
import com.streamvault.domain.model.resolvedWidthDp
import com.streamvault.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun CardStyleSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val prefs = state.cardPrefs

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
                "Card Style",
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
            // ─── Card Size ───
            item {
                Spacer(Modifier.height(4.dp))
                CardSizeSection(prefs.size) { viewModel.updateCardPrefs(prefs.copy(size = it)) }
            }

            item { HorizontalDivider(color = Steel.copy(alpha = 0.2f)) }

            // ─── Hover / Focus Zoom ───
            item {
                CardHoverSection(prefs.hover) { viewModel.updateCardPrefs(prefs.copy(hover = it)) }
            }

            item { HorizontalDivider(color = Steel.copy(alpha = 0.2f)) }

            // ─── Watched Indicator ───
            item {
                WatchedIndicatorSection(prefs.watched) { viewModel.updateCardPrefs(prefs.copy(watched = it)) }
            }

            item { HorizontalDivider(color = Steel.copy(alpha = 0.2f)) }

            // ─── Appearance ───
            item {
                AppearanceSection(prefs.appearance) { viewModel.updateCardPrefs(prefs.copy(appearance = it)) }
            }

            // ─── Reset ───
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { viewModel.updateCardPrefs(CardPrefs()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset All to Defaults", color = Amber)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
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
            Text("Card Size", style = MaterialTheme.typography.titleMedium, color = Snow)
            Spacer(Modifier.height(12.dp))

            // Orientation picker
            Text("Orientation", color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            Text("Size", color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                    Text("Card Width: ${prefs.customWidthDp}dp", color = Silver, fontSize = 12.sp)
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
                        Text("60dp (tiny)", color = Ash, fontSize = 10.sp)
                        Text("300dp (huge)", color = Ash, fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Live preview
            Text("Preview", color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                    Text("Hover / Focus Zoom", color = Snow, fontWeight = FontWeight.SemiBold)
                    Text("Cards grow when hovered, focused, or long-pressed", color = Silver, fontSize = 12.sp)
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
                        Text("Zoom Amount", color = Silver, fontSize = 13.sp)
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
                        Text("Animation Speed", color = Silver, fontSize = 13.sp)
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

                    ToggleRow("Elevation / Shadow", prefs.elevationOnHover) {
                        onUpdate(prefs.copy(elevationOnHover = it))
                    }
                    ToggleRow("Accent Border", prefs.borderOnHover) {
                        onUpdate(prefs.copy(borderOnHover = it))
                    }
                    ToggleRow("Dim Other Cards", prefs.dimOtherCards) {
                        onUpdate(prefs.copy(dimOtherCards = it))
                    }

                    // Live hover preview
                    Spacer(Modifier.height(16.dp))
                    Text("Preview (tap middle card)", color = Silver, fontSize = 13.sp)
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
                    Text("TAP", color = Amber, fontSize = 8.sp, fontWeight = FontWeight.Bold)
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
                    Text("Watched Indicator", color = Snow, fontWeight = FontWeight.SemiBold)
                    Text("Mark movies and shows you've already seen", color = Silver, fontSize = 12.sp)
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

                    Text("Indicator Style", color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

                    ToggleRow("Dim Watched Posters", prefs.dimWatched) {
                        onUpdate(prefs.copy(dimWatched = it))
                    }
                    AnimatedVisibility(visible = prefs.dimWatched) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Dim Amount", color = Silver, fontSize = 12.sp)
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

                    ToggleRow("Progress Bar (partial watches)", prefs.progressBarForPartial) {
                        onUpdate(prefs.copy(progressBarForPartial = it))
                    }
                    ToggleRow("Rewatch Count Badge", prefs.rewatchBadge) {
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
            Text("Appearance", style = MaterialTheme.typography.titleMedium, color = Snow)
            Spacer(Modifier.height(12.dp))

            // Corner radius
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Corner Radius", color = Silver, fontSize = 13.sp)
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
                Text("Card Spacing", color = Silver, fontSize = 13.sp)
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
            Text("Title Position", color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitlePosition.entries.forEach { pos ->
                    FilterChip(
                        selected = prefs.titlePosition == pos,
                        onClick = { onUpdate(prefs.copy(titlePosition = pos)) },
                        label = {
                            Text(
                                when (pos) {
                                    CardTitlePosition.BELOW -> "Below"
                                    CardTitlePosition.OVERLAY_BOTTOM -> "Overlay"
                                    CardTitlePosition.HIDDEN -> "Hidden"
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
            Text("Scroll Animation", color = Silver, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                                    CardScrollAnimation.NONE -> "None"
                                    CardScrollAnimation.FADE_IN -> "Fade In"
                                    CardScrollAnimation.SLIDE_UP -> "Slide Up"
                                    CardScrollAnimation.SCALE_IN -> "Scale In"
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
            ToggleRow("Show Year", prefs.showYear) { onUpdate(prefs.copy(showYear = it)) }
            ToggleRow("Bottom Gradient", prefs.showBottomGradient) { onUpdate(prefs.copy(showBottomGradient = it)) }
            ToggleRow("Card Border", prefs.showBorder) { onUpdate(prefs.copy(showBorder = it)) }
            ToggleRow("Genre Tags", prefs.showGenreTags) { onUpdate(prefs.copy(showGenreTags = it)) }
            ToggleRow("Type Badge (Movie/TV)", prefs.showTypeBadge) { onUpdate(prefs.copy(showTypeBadge = it)) }
            ToggleRow("Runtime", prefs.showRuntime) { onUpdate(prefs.copy(showRuntime = it)) }
        }
    }
}

// ─── Shared Components ───

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
