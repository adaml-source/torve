package com.streamvault.android.ui.mood

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.components.CardSize
import com.streamvault.android.ui.components.PosterCard
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.Charcoal
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.StreamVault
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.recommendation.Mood
import com.streamvault.presentation.mood.MoodMatcherViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodMatcherScreen(
    onMediaClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    viewModel: MoodMatcherViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

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
                text = "What should I watch?",
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
                    text = "Pick your mood",
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
                Spacer(Modifier.height(24.dp))
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
                        text = "We think you'll love these",
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
                        items(state.results, key = { it.item.id }) { result ->
                            Column(
                                modifier = Modifier.width(130.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                PosterCard(
                                    item = result.item,
                                    size = CardSize.MEDIUM,
                                    onClick = { onMediaClick(result.item) },
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = result.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StreamVault.colors.textSecondary,
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
                        text = state.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StreamVault.colors.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}

