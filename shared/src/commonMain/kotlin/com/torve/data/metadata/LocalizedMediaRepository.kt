package com.torve.data.metadata

import com.torve.domain.model.MediaItem
import com.torve.domain.model.extractImdbIdOrNull
import com.torve.presentation.apple.AppleDetailNavigation
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/** Public presentation overlay: does not rewrite saved titles, ratings or playback identity. */
class LocalizedMediaRepository(
    private val api: TmdbApiClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val enrichmentAllowed: suspend () -> Boolean = { true },
    private val presentations: com.torve.domain.repository.MetadataRepository? = null,
) {
    private data class Entry(val value: Any?, val expiresAt: Long)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Entry>()
    private val pending = mutableMapOf<String, Deferred<Any?>>()

    suspend fun cachedPresentations(mediaIds: List<String>, type: String, language: String): List<MediaItem> {
        if (!enrichmentAllowed() || !api.usesTorveBackend) return emptyList()
        val kind = when(type) { "movie" -> "movie"; "tv", "series" -> "tv"; else -> return emptyList() }
        val locale=language.trim().substringBefore('-').substringBefore('_').lowercase()
        if (!locale.matches(Regex("[a-z]{2}"))) return emptyList()
        val ids=mediaIds.mapNotNull { AppleDetailNavigation.catalogId(it) }.distinct().take(12)
        // Publication has its own short deadline in the Apple client. Let a
        // valid cached reply finish on cellular instead of discarding it at
        // 500 ms and launching the slower per-title provider requests again.
        val values=withTimeoutOrNull(3_000) { presentations?.cachedPresentations(kind,ids,locale) }.orEmpty()
        if (!enrichmentAllowed()) return emptyList()
        mutex.withLock {
            for(item in values.filter { !it.presentationPending && (it.posterUrl != null || it.backdropUrl != null) }) cache["presentation:$kind:${item.tmdbId}:$locale:false"] = Entry(item,nowMillis()+1_800_000L)
            while(cache.size>400) cache.remove(cache.keys.first())
        }
        return values
    }

    suspend fun presentation(mediaId: String, type: String, language: String, includeCast: Boolean = false, refresh: Boolean = false): MediaItem? {
        // A cached overlay must not make an opted-out provider appear active.
        if (!enrichmentAllowed()) return null
        val kind = when (type) { "movie" -> "movie"; "tv", "series" -> "tv"; else -> return null }
        val locale = language.trim().substringBefore('-').substringBefore('_').lowercase()
        if (!locale.matches(Regex("[a-z]{2}"))) return null
        if (mediaId.startsWith("direct:") || mediaId.contains("://")) return null
        val imdb = mediaId.extractImdbIdOrNull()
        val id = AppleDetailNavigation.catalogId(mediaId) ?: imdb?.let {
            cached("find:$kind:$it:$locale") {
                val found = api.findByImdbId(it, locale)
                if (kind == "tv") found.tvResults.firstOrNull()?.id else found.movieResults.firstOrNull()?.id
            }
        } ?: return null
        val presentationKey = "presentation:$kind:$id:$locale:$includeCast"
        if (refresh) mutex.withLock { cache.remove(presentationKey) }
        val item = cached(presentationKey) {
            if (api.usesTorveBackend && !includeCast && presentations != null) presentations.getPresentation(kind, id, locale)
            else if (kind == "tv") TmdbMappers.tvToMediaItem(api.getTvPresentation(id, locale, includeCast), locale)
            else TmdbMappers.movieToMediaItem(api.getMoviePresentation(id, locale, includeCast), locale)
        } ?: return null
        if (!enrichmentAllowed()) return null
        if (kind == "tv" && mediaId.startsWith("trakt-calendar:")) {
            val parts = mediaId.split(':')
            val season = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 }
            val episode = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
            if (season != null && episode != null) {
                val detail = cached("season:$id:$season:$locale") { api.getTvSeasonDetail(id, season, locale) }
                val name = detail?.episodes?.firstOrNull { it.episodeNumber == episode }?.name?.takeIf { it.isNotBlank() }
                return item.copy(title = "${item.title} - S${season}E${episode}" + (name?.let { " - $it" } ?: ""))
            }
        }
        return item
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, fetch: suspend () -> T?): T? {
        val request = mutex.withLock {
            cache[key]?.takeIf { nowMillis() < it.expiresAt }?.let { return it.value as T? }
            pending[key] ?: scope.async {
                try {
                    // Cache genuine missing records, never a transient transport failure.
                    val value = try { fetch() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: MetadataHttpException) { if (error.statusCode == 404) null else return@async null }
                        catch (_: Exception) { return@async null }
                    mutex.withLock {
                        // Short negative caching prevents repeated failed requests as cards scroll.
                        if (value !is MediaItem || (!value.presentationPending && (value.posterUrl != null || value.backdropUrl != null))) {
                            cache[key] = Entry(value, nowMillis() + if (value == null) 60_000L else 1_800_000L)
                        }
                        while (cache.size > 400) cache.remove(cache.keys.first())
                    }
                    value
                } finally { mutex.withLock { pending.remove(key) } }
            }.also { pending[key] = it }
        }
        return request.await() as T?
    }
}
