package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractTmdbIdOrNull
import com.torve.domain.model.extractImdbIdOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Presentation and the dedicated logo endpoint are independent, selected-title reads. */
internal suspend fun loadTvSearchPreviewArtwork(
    item: MediaItem,
    presentation: suspend (String, Int) -> MediaItem,
    logo: suspend (String, Int) -> String?,
    findByImdb: suspend (String, String) -> MediaItem? = { _, _ -> null },
): MediaItem = coroutineScope {
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
    val directId = item.tmdbId ?: item.id.extractTmdbIdOrNull()
    val imdb = item.imdbId ?: item.id.extractImdbIdOrNull()
    val id = directId ?: imdb?.let { findByImdb(it, type)?.tmdbId } ?: return@coroutineScope item
    val artwork = async {
        item.logoUrl?.takeIf { it.isNotBlank() } ?: try { logo(type, id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    val fresh = try { presentation(type, id) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    val resolvedLogo = artwork.await()
    item.copy(
        tmdbId = item.tmdbId ?: id,
        logoUrl = item.logoUrl?.takeIf { it.isNotBlank() } ?: fresh?.logoUrl?.takeIf { it.isNotBlank() } ?: resolvedLogo,
        backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: fresh?.backdropUrl,
        overview = item.overview?.takeIf { it.isNotBlank() } ?: fresh?.overview,
        runtime = item.runtime ?: fresh?.runtime,
    )
}
