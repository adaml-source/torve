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
    private const val POSTER_PREFETCH_WIDTH_PX = 384
    private const val POSTER_PREFETCH_HEIGHT_PX = 576
    private const val HERO_PREFETCH_WIDTH_PX = 1280
    private const val HERO_PREFETCH_HEIGHT_PX = 720
    private const val LOGO_PREFETCH_WIDTH_PX = 640
    private const val LOGO_PREFETCH_HEIGHT_PX = 240
    private val lock = Any()
    private val recentUrls = LinkedHashMap<String, Long>()

    private data class PrefetchImage(
        val url: String,
        val widthPx: Int,
        val heightPx: Int,
    )

    fun prefetchRails(
        context: Context,
        screenName: String,
        rails: List<TvContentRail>,
        maxItems: Int = 36,
    ): () -> Unit {
        val items = rails.asSequence()
            .flatMap { rail -> rail.items.asSequence().take(12) }
            .distinctBy { it.tvImagePrefetchKey() }
            .take(maxItems)
            .toList()
        return prefetchMediaItems(context, screenName, items, maxImages = maxItems + 4)
    }

    fun prefetchMediaItems(
        context: Context,
        screenName: String,
        items: List<MediaItem>,
        maxImages: Int = 48,
        includeHeroCandidates: Boolean = true,
    ): () -> Unit {
        val images = sequence {
            if (includeHeroCandidates) {
                items.take(2).forEach { item ->
                    item.backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        yield(PrefetchImage(url, HERO_PREFETCH_WIDTH_PX, HERO_PREFETCH_HEIGHT_PX))
                    }
                    item.logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        yield(PrefetchImage(url, LOGO_PREFETCH_WIDTH_PX, LOGO_PREFETCH_HEIGHT_PX))
                    }
                }
            }
            items.forEach { item ->
                item.posterUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    yield(PrefetchImage(url, POSTER_PREFETCH_WIDTH_PX, POSTER_PREFETCH_HEIGHT_PX))
                }
            }
        }
            .distinctBy { it.url }
            .take(maxImages)
            .toList()
        return prefetchImages(context, screenName, images)
    }

    private fun prefetchImages(context: Context, screenName: String, images: List<PrefetchImage>): () -> Unit {
        val now = System.currentTimeMillis()
        val toPrefetch = synchronized(lock) {
            recentUrls.entries.removeAll { now - it.value > RECENT_URL_TTL_MS }
            images.filter { image ->
                val duplicate = recentUrls.containsKey(image.url)
                if (duplicate) {
                    Log.d("TvImagePrefetch", "image_prefetch_skipped_duplicate screen=$screenName")
                    false
                } else {
                    recentUrls[image.url] = now
                    true
                }
            }.also {
                while (recentUrls.size > RECENT_URL_LIMIT) {
                    val oldest = recentUrls.keys.firstOrNull() ?: break
                    recentUrls.remove(oldest)
                }
            }
        }
        if (toPrefetch.isEmpty()) return {}
        Log.d("TvImagePrefetch", "image_prefetch_batch_size screen=$screenName size=${toPrefetch.size}")
        val loader = context.imageLoader
        val requests = toPrefetch.map { image ->
            Log.d("TvImagePrefetch", "image_prefetch_requested screen=$screenName")
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(image.url)
                    .size(image.widthPx, image.heightPx)
                    .memoryCacheKey(image.url)
                    .diskCacheKey(image.url)
                    .build(),
            )
        }
        return {
            requests.forEach { it.dispose() }
            // Cancelled work must be eligible again when the user returns.
            // Completed images remain in Coil's memory/disk cache.
            synchronized(lock) {
                toPrefetch.forEach { image ->
                    if (recentUrls[image.url] == now) recentUrls.remove(image.url)
                }
            }
        }
    }

    private fun MediaItem.tvImagePrefetchKey(): String =
        tmdbId?.let { "${type.name}:tmdb:$it" }
            ?: imdbId?.takeIf { it.isNotBlank() }?.let { "${type.name}:imdb:${it.lowercase()}" }
            ?: "${type.name}:$id"
}
