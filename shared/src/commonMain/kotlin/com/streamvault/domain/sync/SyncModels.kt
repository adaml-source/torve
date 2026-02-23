package com.streamvault.domain.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncPayload(
    val version: Int = 1,
    val exportedAt: Long,
    val deviceName: String = "",
    val addons: List<SyncAddon> = emptyList(),
    val preferences: List<SyncPreference> = emptyList(),
    val watchProgress: List<SyncProgress> = emptyList(),
    val iptvPlaylists: List<SyncPlaylist> = emptyList(),
    val iptvFavorites: List<SyncFavorite> = emptyList(),
)

@Serializable
data class SyncAddon(
    val manifestUrl: String,
    val isEnabled: Boolean,
    val priority: Int,
)

@Serializable
data class SyncPreference(
    val key: String,
    val value: String,
)

@Serializable
data class SyncProgress(
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val showTitle: String? = null,
    val updatedAt: Long,
)

@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String? = null,
)

@Serializable
data class SyncFavorite(
    val channelId: String,
    val playlistId: String,
    val name: String,
    val groupTitle: String? = null,
)

@Serializable
data class SyncResult(
    val addonsImported: Int = 0,
    val preferencesImported: Int = 0,
    val progressImported: Int = 0,
    val playlistsImported: Int = 0,
    val favoritesImported: Int = 0,
    val conflicts: Int = 0,
)
