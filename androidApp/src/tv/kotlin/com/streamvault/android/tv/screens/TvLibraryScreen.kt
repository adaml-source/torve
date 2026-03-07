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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamvault.android.R
import com.streamvault.android.tv.TvScreenCache
import com.streamvault.android.tv.components.TvContentRail
import com.streamvault.android.tv.components.TvMediaRails
import com.streamvault.android.tv.components.rememberTvFocusMemory
import com.streamvault.android.tv.toMediaItem
import com.streamvault.android.ui.theme.*
import com.streamvault.domain.model.DownloadMediaType
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.WatchlistRepository
import com.streamvault.presentation.download.DownloadCatalogueViewModel
import org.koin.compose.koinInject

private data class TvLibraryUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

private enum class LibraryTab { WATCHLIST, DOWNLOADS }

@Composable
fun TvLibraryScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
) {
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.WATCHLIST.ordinal) }
    val currentTab = LibraryTab.entries[selectedTab]

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab row
        val tabLabels = listOf(
            stringResource(R.string.tv_library_tab_watchlist),
            stringResource(R.string.tv_library_tab_downloads),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        ) {
            itemsIndexed(
                items = tabLabels,
                key = { index, _ -> "tab_$index" },
            ) { index, label ->
                val requester = remember("library_tab_$index") { FocusRequester() }
                if (index == 0) {
                    onFirstContentRequester(requester)
                }
                TvLibraryTabChip(
                    label = label,
                    isSelected = index == selectedTab,
                    modifier = Modifier
                        .focusRequester(requester)
                        .then(
                            if (index == 0) Modifier.focusProperties { left = railFocusRequester }
                            else Modifier
                        ),
                    onFocused = { onContentFocused(requester) },
                    onClick = { selectedTab = index },
                )
            }
        }

        // Content
        when (currentTab) {
            LibraryTab.WATCHLIST -> WatchlistContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onMediaClick = onMediaClick,
                onFirstContentRequester = onFirstContentRequester,
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                onSeeAll = onSeeAll,
                heroOverlay = heroOverlay,
                shouldAutoFocus = shouldAutoFocus,
            )
            LibraryTab.DOWNLOADS -> DownloadsContent(
                railFocusRequester = railFocusRequester,
                headerFocusRequester = headerFocusRequester,
                onMediaClick = onMediaClick,
                onFirstContentRequester = onFirstContentRequester,
                onContentFocused = onContentFocused,
                onMediaFocused = onMediaFocused,
                onSeeAll = onSeeAll,
                shouldAutoFocus = shouldAutoFocus,
            )
            // Recordings tab removed
        }
    }
}

@Composable
private fun WatchlistContent(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    onSeeAll: ((railKey: String, title: String) -> Unit)?,
    heroOverlay: (@Composable () -> Unit)?,
    shouldAutoFocus: Boolean,
) {
    val watchlistRepo: WatchlistRepository = koinInject()
    val focusMemory = rememberTvFocusMemory()

    val watchlistMoviesLabel = stringResource(R.string.tv_section_watchlist_movies)
    val watchlistShowsLabel = stringResource(R.string.tv_section_watchlist_shows)

    var uiState by remember {
        mutableStateOf(TvScreenCache.get<TvLibraryUiState>("library") ?: TvLibraryUiState())
    }

    LaunchedEffect(Unit) {
        uiState = TvLibraryUiState(loading = true)
        uiState = try {
            val allItems = watchlistRepo.getAll()
            val movieItems = allItems.filter { it.mediaType == MediaType.MOVIE }.map { it.toMediaItem() }
            val showItems = allItems.filter { it.mediaType == MediaType.SERIES }.map { it.toMediaItem() }
            val rails = buildList {
                if (movieItems.isNotEmpty()) {
                    add(
                        TvContentRail(
                            key = "watchlist_movies",
                            title = watchlistMoviesLabel,
                            items = movieItems,
                        ),
                    )
                }
                if (showItems.isNotEmpty()) {
                    add(
                        TvContentRail(
                            key = "watchlist_shows",
                            title = watchlistShowsLabel,
                            items = showItems,
                        ),
                    )
                }
            }
            TvLibraryUiState(loading = false, rails = rails).also { TvScreenCache.put("library", it) }
        } catch (t: Throwable) {
            TvLibraryUiState(loading = false, error = t.message ?: "Failed to load library")
        }
    }

    val emptyMessage = uiState.error ?: stringResource(R.string.tv_library_empty)
    TvMediaRails(
        rails = uiState.rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        focusMemory = focusMemory,
        loading = uiState.loading,
        emptyMessage = emptyMessage,
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
    )
}

@Composable
private fun DownloadsContent(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
    onMediaFocused: ((MediaItem) -> Unit)?,
    onSeeAll: ((railKey: String, title: String) -> Unit)?,
    shouldAutoFocus: Boolean,
) {
    val downloadCatalogueViewModel: DownloadCatalogueViewModel = koinInject()
    val catalogueState by downloadCatalogueViewModel.state.collectAsState()
    val focusMemory = rememberTvFocusMemory()

    // loadCatalogue() is already called in the ViewModel's init block;
    // calling it again here would reset isLoading and briefly clear the rails,
    // which can cause focus loss and crashes during navigation.

    val rails = remember(catalogueState.allDownloadedItems, catalogueState.activeDownloads) {
        val items = catalogueState.allDownloadedItems
        val movieItems = items.filter { it.type == DownloadMediaType.MOVIE }.map { dl ->
            MediaItem(
                id = dl.mediaId,
                type = MediaType.MOVIE,
                title = dl.title,
                posterUrl = dl.posterUrl,
            )
        }
        val showItems = items.filter { it.type == DownloadMediaType.EPISODE }.map { dl ->
            MediaItem(
                id = dl.mediaId,
                type = MediaType.SERIES,
                title = dl.title,
                posterUrl = dl.posterUrl,
            )
        }
        val activeItems = catalogueState.activeDownloads
            .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
            .map { dl ->
                val pct = if ((dl.fileSizeBytes ?: 0) > 0) (dl.downloadedBytes * 100 / dl.fileSizeBytes!!) else 0
                MediaItem(
                    id = dl.mediaId,
                    type = if (dl.mediaType == MediaType.SERIES) MediaType.SERIES else MediaType.MOVIE,
                    title = "${dl.title} (${pct}%)",
                    posterUrl = dl.posterUrl,
                )
            }
        buildList {
            if (activeItems.isNotEmpty()) {
                add(TvContentRail(key = "dl_active", title = "Downloading", items = activeItems))
            }
            if (movieItems.isNotEmpty()) {
                add(TvContentRail(key = "dl_movies", title = "Downloaded Movies", items = movieItems.distinctBy { it.id }))
            }
            if (showItems.isNotEmpty()) {
                add(TvContentRail(key = "dl_shows", title = "Downloaded Shows", items = showItems.distinctBy { it.id }))
            }
        }
    }

    val emptyMessage = stringResource(R.string.tv_library_no_downloads)
    TvMediaRails(
        rails = rails,
        railFocusRequester = railFocusRequester,
        headerFocusRequester = headerFocusRequester,
        onMediaClick = onMediaClick,
        onFirstContentRequester = onFirstContentRequester,
        onContentFocused = onContentFocused,
        focusMemory = focusMemory,
        loading = catalogueState.isLoading,
        emptyMessage = emptyMessage,
        onMediaFocused = onMediaFocused,
        onSeeAll = null, // No See All for downloads — items are local, not from metadata API
        shouldAutoFocus = shouldAutoFocus,
    )
}

@Composable
private fun TvLibraryTabChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "tabScale")
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> AmberLight
            isSelected -> Amber.copy(alpha = 0.67f)
            else -> Color.Transparent
        },
        label = "tabBorder",
    )
    val bgColor = when {
        focused -> Gunmetal
        isSelected -> Graphite
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
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected || focused) Snow else Silver,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
