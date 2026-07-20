package com.torve.android.tv

import android.content.Context
import android.util.Log
import coil3.imageLoader
import coil3.request.ImageRequest
import com.torve.android.tv.components.TvContentRail
import com.torve.domain.model.MediaItem

internal object TvImagePrefetcher {
    private const val RECENT_URL_TTL_MS = 10 * 60 * 1000L
    private const val RECENT_URL_LIMIT = 420
    private val lock = Any()
    private val recentUrls = LinkedHashMap<String, Long>()

    fun prefetchRails(
        context: Context,
        screenName: String,
        rails: List<TvContentRail>,
        maxItems: Int = 36,
    ) {
        val items = rails
            .flatMap { rail -> rail.items.take(12) }
            .distinctBy { it.tvImagePrefetchKey() }
            .take(maxItems)
        prefetchMediaItems(context, screenName, items, maxImages = maxItems * 2)
    }

    fun prefetchMediaItems(
        context: Context,
        screenName: String,
        items: List<MediaItem>,
        maxImages: Int = 48,
        includeHeroCandidates: Boolean = true,
    ) {
        val urls = items
            .asSequence()
            .flatMap { item ->
                sequence {
                    if (includeHeroCandidates) {
                        item.backdropUrl?.takeIf { it.isNotBlank() }?.let { yield(it) }
                        item.logoUrl?.takeIf { it.isNotBlank() }?.let { yield(it) }
                    }
                    item.posterUrl?.takeIf { it.isNotBlank() }?.let { yield(it) }
                }
            }
            .distinct()
            .take(maxImages)
            .toList()
        prefetchUrls(context, screenName, urls)
    }

    private fun prefetchUrls(context: Context, screenName: String, urls: List<String>) {
        val now = System.currentTimeMillis()
        val toPrefetch = synchronized(lock) {
            recentUrls.entries.removeAll { now - it.value > RECENT_URL_TTL_MS }
            urls.filter { url ->
                val duplicate = recentUrls.containsKey(url)
                if (duplicate) {
                    Log.d("TvImagePrefetch", "image_prefetch_skipped_duplicate screen=$screenName")
                    false
                } else {
                    recentUrls[url] = now
                    true
                }
            }.also {
                while (recentUrls.size > RECENT_URL_LIMIT) {
                    val oldest = recentUrls.keys.firstOrNull() ?: break
                    recentUrls.remove(oldest)
                }
            }
        }
        if (toPrefetch.isEmpty()) return
        Log.d("TvImagePrefetch", "image_prefetch_batch_size screen=$screenName size=${toPrefetch.size}")
        val loader = context.imageLoader
        toPrefetch.forEach { url ->
            Log.d("TvImagePrefetch", "image_prefetch_requested screen=$screenName")
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .build(),
            )
        }
    }

    private fun MediaItem.tvImagePrefetchKey(): String =
        tmdbId?.let { "${type.name}:tmdb:$it" }
            ?: imdbId?.takeIf { it.isNotBlank() }?.let { "${type.name}:imdb:${it.lowercase()}" }
            ?: "${type.name}:$id"
}
