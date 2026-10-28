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
) {
    private data class Entry(val value: Any?, val expiresAt: Long)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Entry>()
    private val pending = mutableMapOf<String, Deferred<Any?>>()

    suspend fun presentation(mediaId: String, type: String, language: String, includeCast: Boolean = false): MediaItem? {
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
        val item = cached("presentation:$kind:$id:$locale:$includeCast") {
            if (kind == "tv") TmdbMappers.tvToMediaItem(api.getTvPresentation(id, locale, includeCast), locale)
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
                    val value = try { fetch() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    mutex.withLock {
                        // Short negative caching prevents repeated failed requests as cards scroll.
                        cache[key] = Entry(value, nowMillis() + if (value == null) 60_000L else 1_800_000L)
                        while (cache.size > 400) cache.remove(cache.keys.first())
                    }
                    value
                } finally { mutex.withLock { pending.remove(key) } }
            }.also { pending[key] = it }
        }
        return request.await() as T?
    }
}
