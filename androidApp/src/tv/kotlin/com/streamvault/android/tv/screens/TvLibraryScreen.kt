package com.streamvault.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.streamvault.android.R
import com.streamvault.android.tv.components.TvContentRail
import com.streamvault.android.tv.components.TvMediaRails
import com.streamvault.android.tv.components.rememberTvFocusMemory
import com.streamvault.android.tv.toMediaItem
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.model.MediaType
import com.streamvault.domain.repository.WatchlistRepository
import org.koin.compose.koinInject

private data class TvLibraryUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

@Composable
fun TvLibraryScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val watchlistRepo: WatchlistRepository = koinInject()
    val focusMemory = rememberTvFocusMemory()
    val uiState by produceState(initialValue = TvLibraryUiState(), watchlistRepo) {
        value = TvLibraryUiState(loading = true)
        value = try {
            val allItems = watchlistRepo.getAll()
            val movieItems = allItems.filter { it.mediaType == MediaType.MOVIE }.map { it.toMediaItem() }
            val showItems = allItems.filter { it.mediaType == MediaType.SERIES }.map { it.toMediaItem() }
            val rails = buildList {
                if (movieItems.isNotEmpty()) {
                    add(
                        TvContentRail(
                            key = "watchlist_movies",
                            title = "Watchlist Movies",
                            items = movieItems,
                        ),
                    )
                }
                if (showItems.isNotEmpty()) {
                    add(
                        TvContentRail(
                            key = "watchlist_shows",
                            title = "Watchlist TV Shows",
                            items = showItems,
                        ),
                    )
                }
            }
            TvLibraryUiState(loading = false, rails = rails)
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
    )
}

