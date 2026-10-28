package com.torve.android.tv.components

import com.torve.domain.model.MediaItem
import com.torve.domain.model.MediaType

private val episodeInTitle = Regex("\\bS(\\d{1,3})\\s*E(\\d{1,4})\\b", RegexOption.IGNORE_CASE)

/** Episode identity comes from the row, never an arbitrary episode in show metadata. */
internal fun MediaItem.tvPreviewEpisodeCode(): String? {
    if (type != MediaType.SERIES) return null
    val calendar = id.takeIf { it.startsWith("trakt-calendar:") }?.split(':', limit = 5)
    val match = episodeInTitle.find(title)
    val season = calendar?.getOrNull(2)?.toIntOrNull() ?: match?.groupValues?.get(1)?.toIntOrNull()
    val episode = calendar?.getOrNull(3)?.toIntOrNull() ?: match?.groupValues?.get(2)?.toIntOrNull()
    return if (season != null && season >= 0 && episode != null && episode > 0) {
        "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    } else null
}

internal fun MediaItem.tvPreviewShowTitle(): String {
    val match = episodeInTitle.find(title).takeIf { type == MediaType.SERIES } ?: return title
    val before = title.substring(0, match.range.first).trim(' ', '-', '–', '·', '|')
    return before.ifBlank { title.substring(match.range.last + 1).trim(' ', '-', '–', '·', '|') }.ifBlank { title }
}
