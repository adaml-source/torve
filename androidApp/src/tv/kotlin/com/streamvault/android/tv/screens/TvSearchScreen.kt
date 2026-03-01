package com.streamvault.android.tv.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.R
import com.streamvault.android.voice.VoiceInputPhase
import com.streamvault.android.voice.rememberVoiceInputController
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.repository.MetadataRepository
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

@Composable
fun TvSearchScreen(
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    initialQuery: String = "",
) {
    val metadataRepo: MetadataRepository = koinInject()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val inputFocusRequester = remember { FocusRequester() }
    val voiceButtonFocusRequester = remember { FocusRequester() }
    val voiceController = rememberVoiceInputController(
        prompt = "Search for movies and shows",
        onTranscript = { spokenQuery ->
            query = spokenQuery
        },
    )

    val popularQueries = remember {
        listOf("Action", "Comedy", "Sci-Fi", "Drama", "Thriller", "Animation")
    }

    LaunchedEffect(Unit) {
        onFirstContentRequester(inputFocusRequester)
        inputFocusRequester.requestFocus()
    }

    LaunchedEffect(initialQuery) {
        val normalized = initialQuery.trim()
        if (normalized.isNotBlank() && normalized != query) {
            query = normalized
        }
    }

    LaunchedEffect(query) {
        if (query.length < 2) {
            results = emptyList()
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            delay(250)
            results = metadataRepo.searchMulti(query, 1).take(60)
        } catch (t: Throwable) {
            results = emptyList()
            error = t.message ?: "Search failed"
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 40.dp, top = 18.dp, end = 34.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tv_search_hint)) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocusRequester)
                        .focusProperties {
                            left = railFocusRequester
                            right = voiceButtonFocusRequester
                        }
                        .onFocusChanged { if (it.isFocused) onContentFocused(inputFocusRequester) },
                )
                IconButton(
                    onClick = { voiceController.launch() },
                    modifier = Modifier
                        .focusRequester(voiceButtonFocusRequester)
                        .focusProperties { left = inputFocusRequester }
                        .onFocusChanged {
                            if (it.isFocused) {
                                onContentFocused(voiceButtonFocusRequester)
                            }
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.common_search),
                        tint = Color(0xFFD6A45B),
                    )
                }
            }

            when (voiceController.uiState.value.phase) {
                VoiceInputPhase.Listening -> {
                    Text(
                        text = "Listening",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD6A45B),
                    )
                }

                VoiceInputPhase.Processing -> {
                    Text(
                        text = "Processing voice input",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xCCDAE2EF),
                    )
                }

                VoiceInputPhase.Error,
                VoiceInputPhase.Unsupported,
                -> {
                    Text(
                        text = voiceController.uiState.value.message
                            ?: "Voice input is not available on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB8B8),
                    )
                }

                VoiceInputPhase.Idle -> Unit
            }
        }

        Text(
            text = stringResource(R.string.tv_section_popular_searches),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            items(popularQueries) { term ->
                val requester = remember(term) { FocusRequester() }
                TvSearchChip(
                    text = term,
                    modifier = Modifier
                        .focusRequester(requester)
                        .focusProperties { left = railFocusRequester },
                    onFocused = { onContentFocused(requester) },
                    onClick = { query = term },
                )
            }
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color(0xFFD6A45B))
                }
            }

            error != null -> {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFFFB8B8),
                )
            }

            results.isEmpty() -> {
                Text(
                    text = stringResource(R.string.tv_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xCCDAE2EF),
                )
            }

            else -> {
                Text(
                    text = stringResource(R.string.tv_section_search_results),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        results,
                        key = { index, item ->
                            item.tmdbId?.let { "s_${item.type}_$it" } ?: "${item.type}_${item.id}_$index"
                        },
                    ) { index, item ->
                        val requester = remember(index, item.id) { FocusRequester() }
                        TvSearchResultCard(
                            item = item,
                            modifier = Modifier
                                .width(166.dp)
                                .aspectRatio(2f / 3f)
                                .focusRequester(requester)
                                .focusProperties {
                                    if (index % 5 == 0) {
                                        left = railFocusRequester
                                    }
                                },
                            onFocused = { onContentFocused(requester) },
                            onClick = { onMediaClick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSearchChip(
    text: String,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.03f else 1f, label = "chipScale")
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF1F3048) else Color(0xFF172334))
            .border(1.dp, if (focused) Color(0xFFCDA166) else Color(0x334E5C72), RoundedCornerShape(14.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun TvSearchResultCard(
    item: MediaItem,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "resultScale")
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, if (focused) Color(0xFFCFA86C) else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        AsyncImage(
            model = item.posterUrl ?: item.backdropUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xC0101624))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
