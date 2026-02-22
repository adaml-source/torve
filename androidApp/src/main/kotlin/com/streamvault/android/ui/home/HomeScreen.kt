package com.streamvault.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.WatchProgress
import com.streamvault.presentation.home.HomeViewModel
import org.koin.compose.koinInject

@Composable
fun HomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onContinueWatchingClick: (WatchProgress) -> Unit = {},
    viewModel: HomeViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { viewModel.refresh() }) {
                        Text("Failed to load. Tap to retry.")
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Hero banner
                    state.heroItem?.let { hero ->
                        item(key = "hero") {
                            HeroBanner(
                                item = hero,
                                onClick = { onMediaClick(hero) },
                            )
                        }
                    }

                    // Continue Watching shelf
                    if (state.continueWatching.isNotEmpty()) {
                        item(key = "continue_watching") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Continue Watching",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(state.continueWatching, key = { it.mediaId }) { progress ->
                                        ContinueWatchingCard(
                                            progress = progress,
                                            onClick = { onContinueWatchingClick(progress) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Catalog shelves
                    items(state.shelves, key = { it.id }) { shelf ->
                        CatalogShelf(
                            title = shelf.title,
                            items = shelf.items,
                            shelfType = shelf.type,
                            onItemClick = onMediaClick,
                        )
                    }
                }
            }
        }
    }
}
