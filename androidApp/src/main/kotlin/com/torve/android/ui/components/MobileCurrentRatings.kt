package com.torve.android.ui.components

import androidx.compose.runtime.*
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaRatings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/** Only the settled visible title refreshes; the provider itself caches for an hour. */
@Composable
internal fun rememberMobileCurrentRatings(item: MediaItem, active: Boolean): MediaRatings? {
    val enricher: RatingsEnricher = koinInject()
    return rememberMobileCurrentRatings(item, active, enricher::refreshImdb)
}

@Composable
internal fun rememberMobileCurrentRatings(item: MediaItem, active: Boolean,
    refresh: suspend (MediaItem) -> MediaItem?): MediaRatings? {
    var current by remember(item.type, item.id) { mutableStateOf<MediaRatings?>(null) }
    val latest by rememberUpdatedState(item)
    LaunchedEffect(item.type, item.id, active) {
        if (!active || item.isContentPlaceholder || item.isStubDetail) return@LaunchedEffect
        delay(650)
        try {
            val result = withTimeoutOrNull(6_000) { refresh(latest) } ?: return@LaunchedEffect
            ensureActive()
            current = result.ratings
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Leave the saved ratings available offline. */ }
    }
    val fresh = current?.takeIf { it.imdbScore != null } ?: return item.ratings
    return (item.ratings ?: MediaRatings()).copy(imdbScore = fresh.imdbScore, imdbVotes = fresh.imdbVotes)
}
