package com.streamvault.data.sync

import com.streamvault.db.StreamVaultDatabase
import com.streamvault.domain.sync.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SyncRepositoryImpl(
    private val database: StreamVaultDatabase,
    private val json: Json,
) : SyncRepository {

    companion object {
        /** Keys that contain credentials/tokens — never exported. */
        private val SENSITIVE_KEYS = setOf(
            "debrid_api_key",
            "debrid_provider",          // tied to api_key, re-entered on new device
            "debrid_rd_refresh_token",
            "debrid_rd_client_id",
            "debrid_rd_client_secret",
            "debrid_rd_expires_at",
            "trakt_client_id",
            "trakt_client_secret",
            "trakt_access_token",
            "trakt_refresh_token",
            "simkl_client_id",
            "simkl_access_token",
            "kodi_hosts_json",
        )

        /** Heuristic: skip any key that looks credential-like. */
        private fun isSensitive(key: String): Boolean {
            if (key in SENSITIVE_KEYS) return true
            val lower = key.lowercase()
            return listOf("token", "secret", "password", "api_key", "credential")
                .any { it in lower }
        }
    }

    // ── Export ────────────────────────────────────────────────

    override suspend fun exportSyncPayload(): SyncPayload {
        val queries = database.streamVaultQueries

        val addons = queries.getAllAddons().executeAsList().map { row ->
            SyncAddon(
                manifestUrl = row.manifest_url,
                isEnabled = row.is_enabled == 1L,
                priority = row.priority.toInt(),
            )
        }

        val preferences = queries.getAllPreferences().executeAsList()
            .filter { !isSensitive(it.key) }
            .map { SyncPreference(key = it.key, value = it.value_) }

        val progress = queries.getAllProgress().executeAsList().map { row ->
            SyncProgress(
                mediaId = row.media_id,
                mediaType = row.media_type,
                title = row.title,
                posterUrl = row.poster_url,
                backdropUrl = row.backdrop_url,
                positionMs = row.position_ms,
                durationMs = row.duration_ms,
                seasonNumber = row.season_number?.toInt(),
                episodeNumber = row.episode_number?.toInt(),
                showTitle = row.show_title,
                updatedAt = row.updated_at,
            )
        }

        val playlists = queries.getAllPlaylists().executeAsList().map { row ->
            SyncPlaylist(
                id = row.id,
                name = row.name,
                url = row.url,
                epgUrl = row.epg_url,
            )
        }

        val favorites = queries.getAllFavorites().executeAsList().map { row ->
            SyncFavorite(
                channelId = row.channel_id,
                playlistId = row.playlist_id,
                name = row.name,
                groupTitle = row.group_title,
            )
        }

        return SyncPayload(
            exportedAt = Clock.System.now().toEpochMilliseconds(),
            addons = addons,
            preferences = preferences,
            watchProgress = progress,
            iptvPlaylists = playlists,
            iptvFavorites = favorites,
        )
    }

    override suspend fun exportToJson(): String {
        return json.encodeToString(exportSyncPayload())
    }

    // ── Import ───────────────────────────────────────────────

    override suspend fun importSyncPayload(payload: SyncPayload): SyncResult {
        val queries = database.streamVaultQueries
        var addonsImported = 0
        var prefsImported = 0
        var progressImported = 0
        var playlistsImported = 0
        var favoritesImported = 0
        var conflicts = 0

        // Addons: insert if new, update enabled/priority if existing
        for (addon in payload.addons) {
            val existing = queries.getAddonByUrl(addon.manifestUrl).executeAsOneOrNull()
            if (existing == null) {
                // New addon — we only have the URL, not the full manifest.
                // Insert a placeholder that the AddonRepository can refresh later.
                queries.insertAddon(
                    manifest_url = addon.manifestUrl,
                    id = addon.manifestUrl,   // placeholder
                    name = "Synced Addon",    // placeholder
                    version = "0.0.0",
                    description = "Imported via sync — will refresh on next launch",
                    logo = null,
                    manifest_json = "{}",
                    is_enabled = if (addon.isEnabled) 1L else 0L,
                    priority = addon.priority.toLong(),
                    installed_at = Clock.System.now().toEpochMilliseconds(),
                )
                addonsImported++
            } else {
                queries.updateAddonEnabled(
                    is_enabled = if (addon.isEnabled) 1L else 0L,
                    manifest_url = addon.manifestUrl,
                )
                queries.updateAddonPriority(
                    priority = addon.priority.toLong(),
                    manifest_url = addon.manifestUrl,
                )
                addonsImported++
            }
        }

        // Preferences: last-write-wins, skip sensitive keys
        for (pref in payload.preferences) {
            if (isSensitive(pref.key)) continue
            queries.setPreference(pref.key, pref.value)
            prefsImported++
        }

        // Watch progress: max(position) to never lose viewing progress
        for (wp in payload.watchProgress) {
            val local = queries.getProgress(wp.mediaId).executeAsOneOrNull()
            if (local != null && local.position_ms > wp.positionMs) {
                // Local is ahead — keep local
                conflicts++
                continue
            }
            queries.upsertProgress(
                media_id = wp.mediaId,
                media_type = wp.mediaType,
                title = wp.title,
                poster_url = wp.posterUrl,
                backdrop_url = wp.backdropUrl,
                position_ms = wp.positionMs,
                duration_ms = wp.durationMs,
                season_number = wp.seasonNumber?.toLong(),
                episode_number = wp.episodeNumber?.toLong(),
                show_title = wp.showTitle,
                updated_at = wp.updatedAt,
            )
            progressImported++
        }

        // IPTV playlists: insert if URL not already present
        val existingPlaylists = queries.getAllPlaylists().executeAsList()
        val existingUrls = existingPlaylists.map { it.url }.toSet()
        for (pl in payload.iptvPlaylists) {
            if (pl.url in existingUrls) {
                conflicts++
                continue
            }
            queries.insertPlaylist(
                id = pl.id,
                name = pl.name,
                url = pl.url,
                epg_url = pl.epgUrl,
                channel_count = 0,
                last_updated = null,
                type = "m3u",
                server = null,
                username = null,
                password = null,
            )
            playlistsImported++
        }

        // IPTV favorites: upsert by channelId
        for (fav in payload.iptvFavorites) {
            queries.insertFavorite(
                channel_id = fav.channelId,
                playlist_id = fav.playlistId,
                name = fav.name,
                logo_url = null,
                group_title = fav.groupTitle,
                added_at = Clock.System.now().toEpochMilliseconds(),
            )
            favoritesImported++
        }

        return SyncResult(
            addonsImported = addonsImported,
            preferencesImported = prefsImported,
            progressImported = progressImported,
            playlistsImported = playlistsImported,
            favoritesImported = favoritesImported,
            conflicts = conflicts,
        )
    }

    override suspend fun importFromJson(jsonStr: String): SyncResult {
        val payload = json.decodeFromString<SyncPayload>(jsonStr)
        return importSyncPayload(payload)
    }
}
