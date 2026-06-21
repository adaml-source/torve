package com.torve.android.ui.mood

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.LocalRatingPrefs
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Torve
import com.torve.domain.model.MediaItem
import com.torve.domain.model.resolveCardStyle
import com.torve.domain.recommendation.Mood
import com.torve.presentation.mood.MoodMatcherViewModel
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodMatcherScreen(
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    viewModel: MoodMatcherViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )

    CompositionLocalProvider(
        LocalCardStyle provides defaultCardStyle,
        LocalRatingPrefs provides settingsState.ratingPrefs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Obsidian)
                .statusBarsPadding(),
        ) {
        // Top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            BackButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = stringResource(R.string.mood_what_to_watch),
                style = MaterialTheme.typography.titleLarge,
                color = Snow,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Mood selector
            item(key = "mood_header") {
                Text(
                    text = stringResource(R.string.mood_pick_mood),
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
            }

            item(key = "mood_grid") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Mood.entries.forEach { mood ->
                        val isSelected = state.selectedMood == mood
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isSelected) Amber.copy(alpha = 0.2f) else Gunmetal,
                                )
                                .clickable { viewModel.selectMood(mood) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = mood.emoji,
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = mood.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) Amber else Snow,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Powered by ${settingsState.aiProvider.name} — each suggestion uses one API call",
                    style = MaterialTheme.typography.labelSmall,
                    color = Silver,
                )
                Spacer(Modifier.height(16.dp))
            }

            // Results
            if (state.isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Amber, modifier = Modifier.size(40.dp))
                    }
                }
            }

            if (state.results.isNotEmpty()) {
                item(key = "results_header") {
                    Text(
                        text = stringResource(R.string.mood_love_these),
                        style = MaterialTheme.typography.titleMedium,
                        color = Amber,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                item(key = "results_row") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(state.results, key = { index, result -> mediaItemLazyKey(result.item, index) }) { _, result ->
                            Column(
                                modifier = Modifier.width(130.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                PosterCard(
                                    item = result.item,
                                    sizeOverride = CardSize.MEDIUM,
                                    onClick = { onMediaClick(result.item) },
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = result.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Torve.colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (state.error != null) {
                item(key = "error") {
                    Text(
                        text = com.torve.android.error.resolveErrorKey(androidx.compose.ui.platform.LocalContext.current, state.error) ?: stringResource(R.string.error_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Torve.colors.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
    }
}
