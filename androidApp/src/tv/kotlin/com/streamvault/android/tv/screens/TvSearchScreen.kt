package com.streamvault.android.tv.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamvault.android.R
import com.streamvault.android.tv.components.TvClickToEditOutlinedTextField
import com.streamvault.android.ui.theme.*
import com.streamvault.android.voice.VoiceInputPhase
import com.streamvault.android.voice.rememberVoiceInputController
import com.streamvault.data.ai.KeywordSearchService
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.presentation.settings.SettingsViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private enum class SearchMode { STANDARD, AI }

@Composable
fun TvSearchScreen(
    railFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    initialQuery: String = "",
    shouldAutoFocus: Boolean = true,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val settingsViewModel: SettingsViewModel = koinInject()
    val keywordSearchService: KeywordSearchService = koinInject()
    val settingsState by settingsViewModel.state.collectAsState()

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchMode by rememberSaveable { mutableStateOf(SearchMode.STANDARD) }
    var filterType by rememberSaveable { mutableStateOf<String?>(null) } // "movie", "tv", or null (all)
    var aiResultTitle by remember { mutableStateOf<String?>(null) }
    var aiFallback by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val voiceButtonFocusRequester = remember { FocusRequester() }
    val voiceController = rememberVoiceInputController(
        prompt = "Search for movies and shows",
        onTranscript = { spokenQuery ->
            query = spokenQuery
        },
    )

    val hasAiKey = settingsState.activeAiApiKey.isNotBlank()

    val popularQueries = remember {
        listOf("Action", "Comedy", "Sci-Fi", "Drama", "Thriller", "Animation")
    }

    LaunchedEffect(Unit) {
        onFirstContentRequester(inputFocusRequester)
    }

    LaunchedEffect(shouldAutoFocus) {
        if (shouldAutoFocus) {
            inputFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(initialQuery) {
        val normalized = initialQuery.trim()
        if (normalized.isNotBlank() && normalized != query) {
            query = normalized
        }
    }

    // Standard search
    LaunchedEffect(query, searchMode, filterType) {
        if (searchMode != SearchMode.STANDARD) return@LaunchedEffect
        aiResultTitle = null
        aiFallback = false
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
            val raw = metadataRepo.searchMulti(query, 1).take(60)
            results = if (filterType != null) {
                raw.filter {
                    when (filterType) {
                        "movie" -> it.type == MediaType.MOVIE
                        "tv" -> it.type == MediaType.SERIES
                        else -> true
                    }
                }
            } else raw
        } catch (t: Throwable) {
            results = emptyList()
            error = t.message ?: "Search failed"
        } finally {
            loading = false
        }
    }

    // AI search
    LaunchedEffect(query, searchMode) {
        if (searchMode != SearchMode.AI) return@LaunchedEffect
        aiFallback = false
        if (query.length < 2 || !hasAiKey) {
            results = emptyList()
            loading = false
            error = null
            aiResultTitle = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        aiResultTitle = null
        try {
            delay(300)
            val aiResult = keywordSearchService.searchWithAi(
                settingsState.aiProvider,
                settingsState.activeAiApiKey,
                query,
            )
            aiResultTitle = aiResult.title

            val resolvedItems: List<MediaItem> = when (aiResult.mode) {
                "specific" -> {
                    aiResult.specificItems.mapNotNull { item ->
                        try {
                            metadataRepo.getDetail(item.mediaType, item.tmdbId)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
                "person_credits", "person_filtered" -> {
                    val personId = aiResult.personId
                    if (personId != null) {
                        try {
                            metadataRepo.getPersonCredits(personId)
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    } else emptyList()
                }
                else -> { // "discover"
                    try {
                        val mediaType = aiResult.mediaType ?: "movie"
                        val genreString = aiResult.genreIds.takeIf { it.isNotEmpty() }
                            ?.joinToString(",")
                        val keywordString = aiResult.keywordIds.takeIf { it.isNotEmpty() }
                            ?.joinToString(",")
                        metadataRepo.discover(
                            type = mediaType,
                            sortBy = aiResult.sortBy,
                            withGenres = genreString,
                            withKeywords = keywordString,
                            minRating = aiResult.minRating,
                            year = aiResult.yearFrom,
                            yearTo = aiResult.yearTo,
                        ).items.take(60)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
            }

            results = resolvedItems.take(60)
        } catch (_: Throwable) {
            // Fallback to standard search
            aiFallback = true
            aiResultTitle = null
            try {
                results = metadataRepo.searchMulti(query, 1).take(60)
            } catch (t: Throwable) {
                results = emptyList()
                error = t.message ?: "Search failed"
            }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvClickToEditOutlinedTextField(
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
                        tint = Amber,
                    )
                }
            }

            // Search mode toggle row
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val stdRequester = remember { FocusRequester() }
                TvSearchChip(
                    text = stringResource(R.string.tv_search_mode_standard),
                    selected = searchMode == SearchMode.STANDARD,
                    modifier = Modifier
                        .focusRequester(stdRequester)
                        .focusProperties { left = railFocusRequester },
                    onFocused = { onContentFocused(stdRequester) },
                    onClick = { searchMode = SearchMode.STANDARD },
                )

                val aiRequester = remember { FocusRequester() }
                if (hasAiKey) {
                    TvSearchChip(
                        text = stringResource(R.string.tv_search_mode_ai),
                        selected = searchMode == SearchMode.AI,
                        modifier = Modifier.focusRequester(aiRequester),
                        onFocused = { onContentFocused(aiRequester) },
                        onClick = { searchMode = SearchMode.AI },
                    )
                } else {
                    TvSearchChip(
                        text = "${stringResource(R.string.tv_search_mode_ai)} (${stringResource(R.string.tv_search_ai_configure)})",
                        selected = false,
                        modifier = Modifier.focusRequester(aiRequester),
                        onFocused = { onContentFocused(aiRequester) },
                        onClick = { /* disabled — no API key */ },
                    )
                }
            }

            // Type filter row
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val allReq = remember { FocusRequester() }
                TvSearchChip(
                    text = "All",
                    selected = filterType == null,
                    modifier = Modifier.focusRequester(allReq).focusProperties { left = railFocusRequester },
                    onFocused = { onContentFocused(allReq) },
                    onClick = { filterType = null },
                )
                val movieReq = remember { FocusRequester() }
                TvSearchChip(
                    text = "Movies",
                    selected = filterType == "movie",
                    modifier = Modifier.focusRequester(movieReq),
                    onFocused = { onContentFocused(movieReq) },
                    onClick = { filterType = "movie" },
                )
                val tvReq = remember { FocusRequester() }
                TvSearchChip(
                    text = "TV Shows",
                    selected = filterType == "tv",
                    modifier = Modifier.focusRequester(tvReq),
                    onFocused = { onContentFocused(tvReq) },
                    onClick = { filterType = "tv" },
                )
            }

            when (voiceController.uiState.value.phase) {
                VoiceInputPhase.Listening -> {
                    Text(
                        text = stringResource(R.string.tv_voice_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Amber,
                    )
                }

                VoiceInputPhase.Processing -> {
                    Text(
                        text = stringResource(R.string.tv_voice_processing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                }

                VoiceInputPhase.Error,
                VoiceInputPhase.Unsupported,
                -> {
                    Text(
                        text = voiceController.uiState.value.message
                            ?: stringResource(R.string.tv_voice_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ruby.copy(alpha = 0.7f),
                    )
                }

                VoiceInputPhase.Idle -> Unit
            }
        }

        Text(
            text = stringResource(R.string.tv_section_popular_searches),
            style = MaterialTheme.typography.titleLarge,
            color = Snow,
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
                    CircularProgressIndicator(color = Amber)
                }
            }

            error != null -> {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ruby.copy(alpha = 0.7f),
                )
            }

            results.isEmpty() -> {
                Text(
                    text = stringResource(R.string.tv_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Silver,
                )
            }

            else -> {
                // AI result label or fallback notice
                if (aiResultTitle != null && searchMode == SearchMode.AI) {
                    Text(
                        text = stringResource(R.string.tv_search_ai_label, aiResultTitle!!),
                        style = MaterialTheme.typography.titleLarge,
                        color = Amber,
                    )
                } else if (aiFallback) {
                    Text(
                        text = stringResource(R.string.tv_search_ai_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Silver,
                    )
                }

                Text(
                    text = stringResource(R.string.tv_section_search_results),
                    style = MaterialTheme.typography.titleLarge,
                    color = Snow,
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
    selected: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.03f else 1f, label = "chipScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> Amber
            focused -> AmberLight
            else -> Color.Transparent
        },
        label = "chipBorder",
    )
    val bgColor = when {
        selected -> Amber.copy(alpha = 0.15f)
        focused -> Graphite
        else -> Charcoal
    }
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) Amber else Snow,
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
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Color.Transparent,
        label = "resultBorder",
    )
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
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
                .background(Obsidian.copy(alpha = 0.75f))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Snow,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
