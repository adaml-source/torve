package com.torve.android.tv.components

import androidx.compose.runtime.mutableStateMapOf
import com.torve.domain.model.MediaItem

private val localizedPresentation = mutableStateMapOf<String, MediaItem>()

internal fun tvLocalizedPresentationKey(item: MediaItem, owner: String?, language: String): String =
    "${owner ?: "guest"}:$language:${item.type}:${item.tmdbId ?: item.imdbId ?: item.id}"

internal fun hasTvLocalizedPresentation(key: String): Boolean = key in localizedPresentation

internal fun cacheTvLocalizedPresentation(key: String, detail: MediaItem) {
    localizedPresentation[key] = detail
    while (localizedPresentation.size > 512) localizedPresentation.remove(localizedPresentation.keys.first())
}

/** Only presentation changes: provider identity, playback, progress and ratings stay attached to the original item. */
internal fun MediaItem.withTvLocalizedPresentation(detail: MediaItem): MediaItem = copy(
    title = detail.title.takeIf { it.isNotBlank() }?.let { translated ->
        tvPreviewEpisodeCode()?.let { "$it · ${detail.tvPreviewShowTitle()}" } ?: translated
    } ?: title,
    overview = detail.overview?.takeIf { it.isNotBlank() } ?: overview,
    posterUrl = detail.posterUrl?.takeIf { it.isNotBlank() } ?: posterUrl,
    backdropUrl = detail.backdropUrl?.takeIf { it.isNotBlank() } ?: backdropUrl,
    titlelessBackdropUrl = detail.titlelessBackdropUrl?.takeIf { it.isNotBlank() } ?: titlelessBackdropUrl,
    logoUrl = detail.logoUrl?.takeIf { it.isNotBlank() } ?: logoUrl,
    genres = detail.genres.ifEmpty { genres },
)

internal fun localizedTvPresentation(item: MediaItem, owner: String?, language: String, enabled: Boolean = true): MediaItem =
    if (enabled) localizedPresentation[tvLocalizedPresentationKey(item, owner, language)]?.let(item::withTvLocalizedPresentation) ?: item else item
