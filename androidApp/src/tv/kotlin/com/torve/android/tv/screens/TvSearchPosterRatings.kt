package com.torve.android.tv.screens

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import com.torve.data.mdblist.RatingsEnricher
import com.torve.domain.model.MediaItem
import com.torve.domain.model.hasExternalRatingLookupIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

internal fun tvSearchVisibleRatingTargets(items: List<MediaItem>, visibleKeys: List<String>,
    attemptedAt: Map<String, Long>, now: Long): List<MediaItem> {
    val keys = visibleKeys.toSet()
    return items.filter { item ->
        val key = item.tvSearchStableKey()
        key in keys && item.hasExternalRatingLookupIdentity() &&
            item.ratings?.imdbScore?.let { it.isFinite() && it > 0f && it <= 10f } != true &&
            attemptedAt[key]?.let { now - it < 60_000L } != true
    }
}

/** Cover the viewport after scrolling settles, without fetching the entire catalogue. */
@Composable
internal fun TvSearchPosterRatingsEffect(items: List<MediaItem>, grid: LazyGridState,
    scopeKey: Any?, onEnriched: (List<MediaItem>) -> Unit) {
    val ratings: RatingsEnricher = koinInject()
    val currentItems by rememberUpdatedState(items)
    val updated by rememberUpdatedState(onEnriched)
    val attempts = remember(scopeKey) { mutableMapOf<String, Long>() }
    val visibleKeys by remember(grid) {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
    }
    LaunchedEffect(scopeKey, visibleKeys) {
        delay(300)
        val candidates = tvSearchVisibleRatingTargets(currentItems, visibleKeys, attempts, System.currentTimeMillis())
        for (batch in candidates.chunked(4)) {
            try {
                updated(ratings.enrichVisibleImdbList(batch))
                batch.forEach { attempts[it.tvSearchStableKey()] = System.currentTimeMillis() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { batch.forEach { attempts[it.tvSearchStableKey()] = System.currentTimeMillis() } }
        }
    }
}
