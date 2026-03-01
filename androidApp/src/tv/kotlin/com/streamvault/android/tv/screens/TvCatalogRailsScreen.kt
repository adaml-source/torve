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
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.repository.MetadataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koin.compose.koinInject

private data class CatalogRailsUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

@Composable
internal fun TvCatalogRailsScreen(
    mediaType: String,
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val focusMemory = rememberTvFocusMemory()
    val uiState by produceState(initialValue = CatalogRailsUiState(), metadataRepo, mediaType) {
        value = CatalogRailsUiState(loading = true)
        value = try {
            val rails = coroutineScope {
                val trendingDeferred = async { metadataRepo.getTrending(mediaType) }
                val popularDeferred = async { metadataRepo.getPopular(mediaType) }
                val topRatedDeferred = async { metadataRepo.getTopRated(mediaType) }

                val trending = trendingDeferred.await().take(24)
                val popular = popularDeferred.await().take(24)
                val topRated = topRatedDeferred.await().take(24)

                val trendingLabel =
                    if (mediaType == "movie") "Trending Movies" else "Trending TV Shows"
                val popularLabel =
                    if (mediaType == "movie") "Popular Movies" else "Popular TV Shows"
                val topRatedLabel =
                    if (mediaType == "movie") "Top Rated Movies" else "Top Rated TV Shows"

                buildList {
                    if (trending.isNotEmpty()) {
                        add(TvContentRail("trending_$mediaType", trendingLabel, trending))
                    }
                    if (popular.isNotEmpty()) {
                        add(TvContentRail("popular_$mediaType", popularLabel, popular))
                    }
                    if (topRated.isNotEmpty()) {
                        add(TvContentRail("top_rated_$mediaType", topRatedLabel, topRated))
                    }
                }
            }
            CatalogRailsUiState(loading = false, rails = rails)
        } catch (t: Throwable) {
            CatalogRailsUiState(loading = false, error = t.message ?: "Failed to load catalog")
        }
    }

    val emptyMessage = uiState.error ?: stringResource(R.string.tv_no_data)
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

