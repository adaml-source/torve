package com.streamvault.android.tv.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.streamvault.android.R
import com.streamvault.android.tv.TvScreenCache
import com.streamvault.android.tv.toMediaItemOrNull
import com.streamvault.android.tv.components.TvCardStyle
import com.streamvault.android.tv.components.TvContentRail
import com.streamvault.android.tv.components.TvMediaRails
import com.streamvault.android.tv.components.dedupeAcrossRails
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
    onMediaFocused: ((MediaItem) -> Unit)? = null,
    onSeeAll: ((railKey: String, title: String) -> Unit)? = null,
    heroOverlay: (@Composable () -> Unit)? = null,
    shouldAutoFocus: Boolean = true,
) {
    val metadataRepo: MetadataRepository = koinInject()
    val watchProgressRepo: WatchProgressRepository = koinInject()
    val focusMemory = rememberTvFocusMemory()

    val continueWatchingLabel = stringResource(R.string.tv_section_continue_watching)
    val recommendedLabel = stringResource(R.string.tv_section_recommended)
    val trendingMoviesLabel = stringResource(R.string.tv_section_trending_movies)
    val trendingShowsLabel = stringResource(R.string.tv_section_trending_shows)

    var uiState by remember {
        mutableStateOf(TvScreenCache.get<TvHomeUiState>("home") ?: TvHomeUiState())
    }

    LaunchedEffect(Unit) {
        if (uiState.rails.isNotEmpty()) return@LaunchedEffect
        uiState = TvHomeUiState(loading = true)
        uiState = try {
            val rails = coroutineScope {
                val inProgressDeferred = async { watchProgressRepo.getInProgress(20) }
                val trendingMoviesDeferred = async { metadataRepo.getTrending("movie") }
                val trendingShowsDeferred = async { metadataRepo.getTrending("tv") }
                val popularMoviesDeferred = async { metadataRepo.getPopular("movie") }
                val popularShowsDeferred = async { metadataRepo.getPopular("tv") }

                val inProgressRaw = inProgressDeferred.await()
                val inProgress = inProgressRaw
                    .mapNotNull { it.toMediaItemOrNull() }
                    .filter { it.tmdbId != null }
                    .take(20)

                val progressMap = inProgressRaw
                    .filter { it.progressPercent > 0f }
                    .associate { it.mediaId to it.progressPercent }

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
                                title = continueWatchingLabel,
                                items = inProgress,
                                cardStyle = TvCardStyle.BACKDROP,
                                progressByMediaId = progressMap,
                            ),
                        )
                    }
                    if (recommended.isNotEmpty()) {
                        add(
                            TvContentRail(
                                key = "recommended",
                                title = recommendedLabel,
                                items = recommended,
                            ),
                        )
                    }
                    if (trendingMovies.isNotEmpty()) {
                        add(
                            TvContentRail(
                                key = "trending_movies",
                                title = trendingMoviesLabel,
                                items = trendingMovies,
                            ),
                        )
                    }
                    if (trendingShows.isNotEmpty()) {
                        add(
                            TvContentRail(
                                key = "trending_shows",
                                title = trendingShowsLabel,
                                items = trendingShows,
                            ),
                        )
                    }
                }.dedupeAcrossRails()
            }
            TvHomeUiState(loading = false, rails = rails).also { TvScreenCache.put("home", it) }
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
        onMediaFocused = onMediaFocused,
        onSeeAll = onSeeAll,
        heroOverlay = heroOverlay,
        shouldAutoFocus = shouldAutoFocus,
    )
}
