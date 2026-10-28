package com.torve.presentation.catalog

import com.torve.domain.model.CatalogShelf
import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.PreferencesRepository
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A local first-page presentation, in the existing account/profile preference namespace. */
@Serializable
internal data class CatalogDiscoverySnapshot(
    val savedAtMs: Long,
    val mediaType: String,
    val language: String,
    val continueWatching: List<WatchProgress> = emptyList(),
    val trending: List<MediaItem> = emptyList(),
    val popular: List<MediaItem> = emptyList(),
    val topRated: List<MediaItem> = emptyList(),
)

@Serializable
private data class HomeDiscoverySeed(
    val savedAtMs: Long,
    val shelves: List<CatalogShelf> = emptyList(),
    val continueWatching: List<WatchProgress> = emptyList(),
)

internal class CatalogDiscoverySnapshotStore(
    private val prefs: PreferencesRepository,
    private val mediaType: String,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "catalog_discovery_snapshot_v1_$mediaType"
    private suspend fun language() = prefs.getString("app_language").orEmpty()
    private fun fresh(savedAt: Long) = now() - savedAt in 0..7L * 24 * 60 * 60 * 1000

    suspend fun read(): CatalogDiscoverySnapshot? = withContext(ioDispatcher) {
        try {
            val language = language()
            prefs.getString(key)?.let { raw ->
                val saved = json.decodeFromString<CatalogDiscoverySnapshot>(raw)
                if (saved.mediaType != mediaType || saved.language != language) return@withContext null
                if (saved.mediaType == mediaType && saved.language == language && fresh(saved.savedAtMs)) return@withContext saved
            }
            // Upgrading users already have these rows in Home. Reuse those titles,
            // instead of sending duplicate catalogue requests before the first tab paint.
            val home = prefs.getString("home_snapshot_v5")?.let { json.decodeFromString<HomeDiscoverySeed>(it) }
                ?.takeIf { fresh(it.savedAtMs) } ?: return@withContext null
            val type = if (mediaType == "tv") MediaType.SERIES else MediaType.MOVIE
            fun shelf(id: String) = home.shelves.firstOrNull { it.id == id }?.items.orEmpty().filter { it.type == type }
            CatalogDiscoverySnapshot(home.savedAtMs, mediaType, language,
                home.continueWatching.filter { it.mediaType == type },
                shelf(if (mediaType == "tv") "trending-tv" else "trending-movies"),
                shelf(if (mediaType == "tv") "popular-tv" else "popular-movies"),
                shelf(if (mediaType == "tv") "top-rated-tv" else "top-rated"))
                .takeIf { it.trending.isNotEmpty() || it.popular.isNotEmpty() || it.topRated.isNotEmpty() || it.continueWatching.isNotEmpty() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun write(state: CatalogUiState) = withContext(ioDispatcher) {
        if (state.providerId != null) return@withContext
        try {
            val snapshot = CatalogDiscoverySnapshot(now(), mediaType, language(), state.continueWatching.take(20),
                state.trendingItems.take(20), state.popularItems.take(20), state.topRatedItems.take(20))
            prefs.setString(key, json.encodeToString(snapshot))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Display never depends on a cache write. */ }
    }
}
