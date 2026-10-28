package com.torve.presentation.apple

import com.torve.data.addon.StremioMeta
import com.torve.data.addon.toMediaItem
import com.torve.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A catalogue selection remains usable even when it has no numeric TMDB identity. */
object AppleCatalogueDetail {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(item: MediaItem): String = json.encodeToString(item)
    fun decode(value: String): MediaItem = json.decodeFromString(value)

    internal suspend fun resolve(
        seed: MediaItem,
        addons: List<InstalledAddon>,
        fetchMeta: suspend (InstalledAddon, String, String) -> StremioMeta?,
        enrich: suspend (MediaItem) -> MediaItem?,
    ): MediaItem {
        if (seed.catalogueNotice != null) return seed
        val type = if (seed.type == MediaType.SERIES) "series" else "movie"
        var item = seed
        val providers = addons.filter { addon ->
            addon.isEnabled && "meta" in addon.manifest.resources &&
                (addon.manifest.types.isEmpty() || type in addon.manifest.types) &&
                (addon.manifest.idPrefixes.isEmpty() || addon.manifest.idPrefixes.any(seed.id::startsWith))
        }.sortedBy { it.priority }.take(3)
        for (addon in providers) {
            val meta = optional { fetchMeta(addon, type, seed.id) } ?: continue
            // A provider must not redirect a selected title to a different ID/type.
            val acceptedTypes = if (type == "series") listOf("series", "tv") else listOf("movie")
            if (meta.id != seed.id || (meta.type.isNotBlank() && meta.type !in acceptedTypes)) continue
            item = fillMissing(seed, meta.copy(type = type).toMediaItem())
            break
        }
        return optional { enrich(item) }?.takeIf { it.type == seed.type }?.let { fillMissing(item, it) } ?: item
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun fillMissing(item: MediaItem, detail: MediaItem): MediaItem = item.copy(
        // Catalogue IDs are also playback IDs; enrichment only adds the resolved TMDB ID.
        tmdbId = item.tmdbId ?: detail.tmdbId,
        imdbId = item.imdbId ?: detail.imdbId,
        title = item.title.ifBlank { detail.title },
        year = item.year ?: detail.year,
        overview = item.overview?.takeIf { it.isNotBlank() } ?: detail.overview,
        posterUrl = item.posterUrl?.takeIf { it.isNotBlank() } ?: detail.posterUrl,
        backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: detail.backdropUrl,
        logoUrl = item.logoUrl ?: detail.logoUrl,
        rating = item.rating ?: detail.rating,
        ratings = item.ratings ?: detail.ratings,
        runtime = item.runtime ?: detail.runtime,
        genres = item.genres.ifEmpty { detail.genres },
        cast = item.cast.ifEmpty { detail.cast },
        director = item.director ?: detail.director,
        releaseDate = item.releaseDate ?: detail.releaseDate,
        status = item.status ?: detail.status,
        trailerKey = item.trailerKey ?: detail.trailerKey,
        seasons = item.seasons.ifEmpty { detail.seasons },
        tagline = item.tagline ?: detail.tagline,
    )
}
