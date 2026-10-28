package com.torve.android.ui.image

import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.PersonSummary

/**
 * Warms only the next small, user-visible image window. Coil still owns cache,
 * request coalescing, sizing, and cancellation; this object merely moves likely
 * next requests ahead of a user's scroll so posters and people do not pop in late.
 */
object MobileImagePrefetcher {
    private const val RECENT_URL_TTL_MS = 5 * 60 * 1000L
    private const val RECENT_URL_LIMIT = 240
    private val lock = Any()
    private val recentUrls = LinkedHashMap<String, Long>()

    fun prefetchCatalog(context: Context, items: List<MediaItem>) {
        enqueue(
            context = context,
            urls = buildList {
                items.take(18).forEach { item ->
                    add(item.posterUrl)
                    if (size < 4) add(item.backdropUrl)
                }
            },
            widthPx = 420,
            heightPx = 630,
        )
    }

    fun prefetchHome(
        context: Context,
        shelves: List<CatalogShelf>,
        actors: List<PersonSummary>,
        directors: List<PersonSummary>,
    ) {
        prefetchCatalog(
            context,
            shelves.take(3).flatMap { shelf -> shelf.items.take(8) },
        )
        enqueue(
            context = context,
            urls = (actors.take(8) + directors.take(6)).map { it.profileUrl },
            widthPx = 192,
            heightPx = 192,
        )
    }

    fun prefetchDetail(
        context: Context,
        item: MediaItem,
        similar: List<MediaItem>,
    ) {
        enqueue(
            context = context,
            urls = buildList {
                add(item.backdropUrl)
                add(item.posterUrl)
                add(item.logoUrl)
            },
            widthPx = 1280,
            heightPx = 720,
        )
        enqueue(
            context = context,
            urls = buildList {
                add(item.directorProfileUrl)
                item.cast.take(15).forEach { add(it.profileUrl) }
            },
            widthPx = 192,
            heightPx = 192,
        )
        prefetchCatalog(context, similar.take(10))
    }

    private fun enqueue(
        context: Context,
        urls: List<String?>,
        widthPx: Int,
        heightPx: Int,
    ) {
        val now = System.currentTimeMillis()
        val pending = synchronized(lock) {
            recentUrls.entries.removeAll { now - it.value > RECENT_URL_TTL_MS }
            urls.asSequence()
                .mapNotNull { it?.trim()?.takeIf(::isUsableRemoteUrl) }
                .distinct()
                .filter { url ->
                    if (recentUrls.containsKey(url)) {
                        false
                    } else {
                        recentUrls[url] = now
                        true
                    }
                }
                .toList()
                .also {
                    while (recentUrls.size > RECENT_URL_LIMIT) {
                        recentUrls.remove(recentUrls.keys.firstOrNull() ?: break)
                    }
                }
        }
        val imageLoader = context.imageLoader
        pending.forEach { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(widthPx, heightPx)
                    .build(),
            )
        }
    }

    private fun isUsableRemoteUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://")
}
