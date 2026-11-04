package com.torve.android.tv.screens

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType
import com.torve.domain.model.extractTmdbIdOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Presentation and the dedicated logo endpoint are independent, selected-title reads. */
internal suspend fun loadTvSearchPreviewArtwork(
    item: MediaItem,
    presentation: suspend (String, Int) -> MediaItem,
    logo: suspend (String, Int) -> String?,
): MediaItem = coroutineScope {
    val id = item.tmdbId ?: item.id.extractTmdbIdOrNull() ?: return@coroutineScope item
    val type = if (item.type == MediaType.SERIES) "tv" else "movie"
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
        logoUrl = item.logoUrl?.takeIf { it.isNotBlank() } ?: fresh?.logoUrl?.takeIf { it.isNotBlank() } ?: resolvedLogo,
        backdropUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: fresh?.backdropUrl,
        overview = item.overview?.takeIf { it.isNotBlank() } ?: fresh?.overview,
        runtime = item.runtime ?: fresh?.runtime,
    )
}
