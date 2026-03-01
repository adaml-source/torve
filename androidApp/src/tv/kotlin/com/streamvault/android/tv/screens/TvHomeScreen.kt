package com.streamvault.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.streamvault.android.R
import com.streamvault.android.tv.toMediaItemOrNull
import com.streamvault.android.tv.components.TvContentRail
import com.streamvault.android.tv.components.TvMediaRails
import com.streamvault.android.tv.components.rememberTvFocusMemory
import com.streamvault.domain.model.MediaItem
import com.streamvault.domain.repository.MetadataRepository
import com.streamvault.domain.repository.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koin.compose.koinInject

private data class TvHomeUiState(
    val loading: Boolean = true,
    val rails: List<TvContentRail> = emptyList(),
    val error: String? = null,
)

@Composable
fun TvHomeScreen(
    railFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    onMediaClick: (MediaItem) -> Unit,
    onFirstContentRequester: (FocusRequester) -> Unit,
    onContentFocused: (FocusRequester) -> Unit,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val watchProgressRepo: WatchProgressRepository = koinInject()
    val focusMemory = rememberTvFocusMemory()

    val uiState by produceState(initialValue = TvHomeUiState(), metadataRepo, watchProgressRepo) {
        value = TvHomeUiState(loading = true)
        value = try {
            val rails = coroutineScope {
                val inProgressDeferred = async { watchProgressRepo.getInProgress(20) }
                val trendingMoviesDeferred = async { metadataRepo.getTrending("movie") }
                val trendingShowsDeferred = async { metadataRepo.getTrending("tv") }
                val popularMoviesDeferred = async { metadataRepo.getPopular("movie") }
                val popularShowsDeferred = async { metadataRepo.getPopular("tv") }

                val inProgress = inProgressDeferred.await()
                    .mapNotNull { it.toMediaItemOrNull() }
                    .filter { it.tmdbId != null }
                    .take(20)

                val trendingMovies = trendingMoviesDeferred.await().take(24)
                val trendingShows = trendingShowsDeferred.await().take(24)
                val recommended = (popularMoviesDeferred.await() + popularShowsDeferred.await())
                    .distinctBy { it.tmdbId ?: "${it.type}:${it.id}" }
                    .take(24)

                buildList {
                    if (inProgress.isNotEmpty()) {
                        add(
                            TvContentRail(
                                key = "continue_watching",
                                title = "Continue Watching",
                                items = inProgress,
                            ),
                        )
                    }
                    if (recommended.isNotEmpty()) {
                        add(
                            TvContentRail(
                                key = "recommended",
                                title = "Recommended",
                                items = recommended,
                            ),
                        )
                    }
                    if (trendingMovies.isNotEmpty()) {
                        add(
                            TvContentRail(
                                key = "trending_movies",
                                title = "Trending Movies",
                                items = trendingMovies,
                            ),
                        )
                    }
                    if (trendingShows.isNotEmpty()) {
                        add(
                            TvContentRail(
                                key = "trending_shows",
                                title = "Trending TV Shows",
                                items = trendingShows,
                            ),
                        )
                    }
                }
            }
            TvHomeUiState(loading = false, rails = rails)
        } catch (t: Throwable) {
            TvHomeUiState(loading = false, error = t.message ?: "Failed to load home content")
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
        loading = uiState.loading,
        emptyMessage = emptyMessage,
        focusMemory = focusMemory,
    )
}

