package com.torve.presentation.apple

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelCategory
import com.torve.domain.model.ChannelContentType
import com.torve.domain.repository.VodCategoryTypeCount

/** Separate provider movie and series categories even when their labels are identical. */
object AppleVodCatalogPolicy {
    fun categories(values: List<VodCategoryTypeCount>, type: ChannelContentType): List<ChannelCategory> {
        require(type == ChannelContentType.VOD_MOVIE || type == ChannelContentType.VOD_SERIES)
        return values.filter { it.contentType == type && it.count > 0 }
            .groupBy { it.groupTitle }
            .map { (name, counts) -> ChannelCategory(name, counts.sumOf { it.count }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
            .sortedBy { it.name.lowercase() }
    }

    /** A catalogue series ID is not a playable episode ID. */
    fun isPlayable(item: Channel): Boolean =
        item.url.isNotBlank() && when (item.contentType) {
            ChannelContentType.VOD_MOVIE -> true
            ChannelContentType.VOD_SERIES -> !item.kodiProps["vod_episode_id"].isNullOrBlank()
            else -> false
        }
}
