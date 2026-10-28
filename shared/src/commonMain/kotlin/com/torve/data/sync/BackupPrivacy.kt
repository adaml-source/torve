package com.torve.data.sync

import com.torve.domain.sync.SyncPayload

/** Deliberately excludes connections, arbitrary preference blobs and all private URLs. */
val BACKUP_PREFERENCE_KEYS = setOf(
    "theme_mode", "app_language", "content_region_code", "rating_display_prefs", "card_prefs",
    "card_style_presets", "card_style_default_preset_id", "home_layout_source", "home_section_configs",
    "home_layout_order", "enabled_streaming_services", "metadata_tmdb_catalogue_enabled",
    "metadata_tmdb_enrichment_enabled", "hide_unwatched_episode_spoilers",
    "preferred_audio_language", "preferred_subtitle_language", "subtitles_enabled_default",
)

fun safeBackupPreference(key: String, value: String): Boolean {
    if (key !in BACKUP_PREFERENCE_KEYS || value.length > 65_536) return false
    if (Regex("(?i)(://|www\\.|%[0-9a-f]{2}|password|token|credential|api[_-]?key|authorization|cookie)").containsMatchIn(value)) return false
    return true
}

internal fun SyncPayload.withoutBackupSecrets(): SyncPayload = copy(
    addons = emptyList(), channelPlaylists = emptyList(), channelFavorites = emptyList(),
    integrationSecrets = emptyList(), subscriptionToken = null, deviceName = "",
    preferences = preferences.filter { safeBackupPreference(it.key, it.value) },
    watchProgress = watchProgress.filter { safeBackupIdentity(it.mediaId) }.map {
        it.copy(posterUrl = null, backdropUrl = null)
    },
)

internal fun safeBackupIdentity(value: String): Boolean =
    value.length in 1..256 && value.all { it.isLetterOrDigit() || it in ":_- ." } && !value.contains("..")
