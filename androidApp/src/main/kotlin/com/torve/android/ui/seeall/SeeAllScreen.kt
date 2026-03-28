package com.torve.android.ui.seeall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.components.CardSize
import com.torve.android.ui.components.LocalCardStyle
import com.torve.android.ui.components.PosterCard
import com.torve.android.ui.components.mediaItemLazyKey
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Snow
import com.torve.domain.model.MediaItem
import com.torve.domain.model.resolveCardStyle
import com.torve.presentation.seeall.SeeAllViewModel
import com.torve.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject

@Composable
fun SeeAllScreen(
    sectionId: String,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    viewModel: SeeAllViewModel = koinInject(),
    settingsViewModel: SettingsViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val defaultCardStyle = resolveCardStyle(
        presets = settingsState.cardStylePresets,
        presetId = null,
        globalDefaultPresetId = settingsState.globalDefaultPresetId,
    )
    val gridState = rememberLazyGridState()

    LaunchedEffect(sectionId) { viewModel.loadSection(sectionId) }

    // Infinite scroll trigger
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= state.items.size - 6 && state.hasMore && !state.isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    CompositionLocalProvider(LocalCardStyle provides defaultCardStyle) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                state.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }

        if (state.items.isEmpty() && state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber, modifier = Modifier.size(48.dp))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(state.items, key = { index, item -> mediaItemLazyKey(item, index) }) { _, item ->
                    PosterCard(
                        item = item,
                        sizeOverride = CardSize.SMALL,
                        onClick = { onMediaClick(item) },
                    )
                }

                if (state.isLoading && state.items.isNotEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Amber, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
    }
}
