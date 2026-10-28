package com.torve.android.tv.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.model.MediaItem
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

@Composable
internal fun RememberFocusedTvImdb(item: MediaItem?, screen: String) {
    val ratings: RatingsEnricher = koinInject()
    val settings: SettingsViewModel = koinInject()
    val state by settings.state.collectAsState()
    RefreshFocusedTvImdb(item, "${state.personalDataUserId}:$screen", ratings::refreshImdb)
}

/** Independent of missing artwork/details: a complete saved card can have an old score. */
@Composable
internal fun RefreshFocusedTvImdb(item: MediaItem?, owner: String,
    refresh: suspend (MediaItem) -> MediaItem?) {
    val current = item?.takeIf { it.isTvPreviewTitle() }?.let(::withCachedTvBrowsePreviewEnrichment)
    LaunchedEffect(owner, current?.type, current?.id, current?.tmdbId, current?.imdbId) {
        val title = current ?: return@LaunchedEffect
        // Passing a poster with the remote must not start another lookup.
        delay(650)
        try {
            val result = withTimeoutOrNull(6_000) { refresh(title) } ?: return@LaunchedEffect
            ensureActive()
            cacheTvFocusedImdbRefresh(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the saved score when the provider is unavailable. */ }
    }
}
