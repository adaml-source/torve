package com.torve.data.catalog

import com.torve.data.metadata.TmdbGenres
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Walks every (mediaType, genreId) bucket and refreshes the
 * CatalogTopCacheRepository in the background. Only re-fetches stale
 * buckets (older than [refreshIntervalMs]) so a typical session is a
 * no-op after the first run.
 *
 * Started once from the app shell on boot. Subsequent calls to
 * [start] are no-ops while a refresh is already running.
 */
class CatalogTopCacheWorker(
    private val repository: CatalogTopCacheRepository,
    /**
     * Optional poster pre-fetcher. After each genre's metadata refresh
     * the worker hands the top-N poster URLs to this callback so the
     * desktop image cache can pre-warm its disk store. By the time the
     * user clicks a genre chip, posters render from disk-cache without
     * a network fetch — eliminating the "first click is slow" issue.
     * Null = no pre-fetch (e.g. on platforms without an image cache).
     */
    private val posterPrefetcher: (suspend (List<String>) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) {
    private val refreshIntervalMs: Long = 24L * 60L * 60L * 1000L
    private val perGenreDelayMs: Long = 250L
    /** Cap on how many posters per genre we pre-fetch. 100 ≈ 5 screens
     *  of scroll on a typical desktop layout, enough to cover most
     *  first-glance scenarios without exhausting the 500MB disk cap. */
    private val posterPrefetchLimit: Int = 100
    private var currentJob: Job? = null

    /** Observable progress for UI banners. */
    data class Progress(
        val running: Boolean = false,
        val totalGenres: Int = 0,
        val processedGenres: Int = 0,
        val currentLabel: String = "",
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    fun start() {
        if (currentJob?.isActive == true) return
        currentJob = scope.launch {
            runOnce()
        }
    }

    suspend fun runNow() {
        if (currentJob?.isActive == true) return
        runOnce()
    }

    private suspend fun runOnce() {
        val movieGenres = TmdbGenres.MOVIE_GENRES.entries
        val tvGenres = TmdbGenres.TV_GENRES.entries

        val combined = buildList {
            movieGenres.forEach { (id, label) -> add(Triple("movie", id, label)) }
            tvGenres.forEach { (id, label) -> add(Triple("tv", id, label)) }
        }

        // Skip the run if everything is fresh -- no need to flash a
        // progress banner just to confirm no work was needed.
        val staleBuckets = combined.filter { (mt, gid, _) ->
            repository.isStale(mt, gid, refreshIntervalMs)
        }
        if (staleBuckets.isEmpty() && posterPrefetcher == null) {
            println("CATALOG_TOP_CACHE_WORKER: all buckets fresh, skipping")
            return
        }

        _progress.value = Progress(
            running = true,
            totalGenres = combined.size,
            processedGenres = 0,
            currentLabel = "",
        )

        var refreshed = 0
        var skipped = 0

        for ((index, triple) in combined.withIndex()) {
            val (mediaType, genreId, genreLabel) = triple
            _progress.value = _progress.value.copy(
                processedGenres = index,
                currentLabel = "$genreLabel (${if (mediaType == "movie") "Movies" else "TV"})",
            )
            val stale = repository.isStale(mediaType, genreId, refreshIntervalMs)
            // Pre-warm posters even when the metadata cache is fresh —
            // disk-image cache may have been evicted by other browsing
            // since the last metadata refresh, so always re-prime.
            if (stale) {
                try {
                    repository.refreshGenre(mediaType, genreId)
                    refreshed++
                } catch (e: Exception) {
                    println("CATALOG_TOP_CACHE_WORKER: refresh failed mediaType=$mediaType genre=$genreId error=${e.message}")
                }
                delay(perGenreDelayMs)
            } else {
                skipped++
            }

            // Prefetch posters for this genre's top N items so first-click
            // renders are instant from disk cache.
            val prefetcher = posterPrefetcher
            if (prefetcher != null) {
                val urls = repository.getTop(mediaType, genreId, limit = posterPrefetchLimit)
                    .mapNotNull { it.posterUrl }
                if (urls.isNotEmpty()) {
                    runCatching { prefetcher(urls) }
                        .onFailure { println("CATALOG_TOP_CACHE_WORKER: prefetch failed mediaType=$mediaType genre=$genreId error=${it.message}") }
                }
            }
        }

        println("CATALOG_TOP_CACHE_WORKER: pass complete refreshed=$refreshed skipped=$skipped")
        _progress.value = _progress.value.copy(
            running = false,
            processedGenres = combined.size,
            currentLabel = "",
        )
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
    }
}
