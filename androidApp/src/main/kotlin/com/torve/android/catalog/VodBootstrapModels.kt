package com.torve.android.catalog

import com.torve.domain.model.Channel
import com.torve.domain.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val VOD_BOOTSTRAP_CATEGORIES_PREFIX = "vod_bootstrap_categories_v1_"
internal const val VOD_BOOTSTRAP_DISPLAY_PREFIX = "vod_bootstrap_display_v1_"
internal const val CHANNELS_BOOTSTRAP_SELECTED_PLAYLIST_PREFIX = "channels_bootstrap_selected_playlist_"

internal val VodBootstrapJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

@Serializable
internal data class VodBootstrapCategory(
    val id: String,
    val label: String,
    val rawNames: List<String>,
    val count: Long,
    val type: VodBootstrapCategoryType,
    val language: String? = null,
    val pinned: Boolean = false,
    val movieCount: Long = count,
    val showCount: Long = count,
)

@Serializable
internal enum class VodBootstrapCategoryType {
    FAVORITES,
    ALL_MOVIES,
    ALL_SHOWS,
    PROVIDER,
}

@Serializable
internal data class VodBootstrapShelf(
    val entries: List<VodBootstrapShelfEntry> = emptyList(),
)

@Serializable
internal data class VodBootstrapShelfEntry(
    val sourceId: String,
    val sourceOrder: Int,
    val channel: Channel,
    val item: MediaItem,
    val searchTitle: String,
    val language: String? = null,
    val category: String,
)

internal fun vodCategoryBootstrapKey(userId: String, playlistId: String): String {
    return "$VOD_BOOTSTRAP_CATEGORIES_PREFIX${userId.hashCode()}_${playlistId.hashCode()}"
}

internal fun vodDisplayShelfBootstrapKey(userId: String, playlistId: String, shelfKey: String): String {
    return "$VOD_BOOTSTRAP_DISPLAY_PREFIX${userId.hashCode()}_${playlistId.hashCode()}_${shelfKey.hashCode()}"
}

internal fun channelsBootstrapSelectedPlaylistKey(userId: String): String {
    return "$CHANNELS_BOOTSTRAP_SELECTED_PLAYLIST_PREFIX$userId"
}
