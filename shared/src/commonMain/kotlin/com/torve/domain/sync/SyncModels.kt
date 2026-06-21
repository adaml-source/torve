package com.torve.domain.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncPayload(
    val schemaVersion: Int = 1,
    val version: Int = 1,
    val exportedAt: Long,
    val deviceName: String = "",
    val addons: List<SyncAddon> = emptyList(),
    val preferences: List<SyncPreference> = emptyList(),
    val watchProgress: List<SyncProgress> = emptyList(),
    @SerialName("iptvPlaylists")
    val channelPlaylists: List<SyncPlaylist> = emptyList(),
    @SerialName("iptvFavorites")
    val channelFavorites: List<SyncFavorite> = emptyList(),
    // Legacy field retained only for backward compatibility with older exports.
    // New exports leave this null and imports ignore it.
    val subscriptionToken: String? = null,
    val integrationSecrets: List<SyncIntegrationSecret> = emptyList(),
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
    val type: String = "m3u",
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class SyncFavorite(
    val channelId: String,
    val playlistId: String,
    val name: String,
    val groupTitle: String? = null,
)

@Serializable
data class SyncIntegrationSecret(
    val key: String,
    val value: String,
)

@Serializable
data class SyncResult(
    val addonsImported: Int = 0,
    val preferencesImported: Int = 0,
    val progressImported: Int = 0,
    val playlistsImported: Int = 0,
    val favoritesImported: Int = 0,
    val secretsImported: Int = 0,
    val conflicts: Int = 0,
)
