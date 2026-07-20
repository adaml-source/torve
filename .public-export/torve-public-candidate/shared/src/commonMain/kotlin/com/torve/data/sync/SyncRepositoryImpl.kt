package com.torve.data.sync

import com.torve.data.auth.UserIdProvider
import com.torve.data.channels.playlistIdentityFor
import com.torve.db.TorveDatabase
import com.torve.domain.model.CardOrientation
import com.torve.domain.model.CardSizePreset
import com.torve.domain.model.CardStyle
import com.torve.domain.model.CardStylePreset
import com.torve.domain.model.HomeSectionConfig
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import com.torve.domain.model.defaultTorveWeights
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.sync.*
import com.torve.presentation.settings.SettingsViewModel
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val SENSITIVE_PREFERENCE_KEYS = setOf(
    "debrid_api_key",
    "debrid_rd_refresh_token",
    "debrid_rd_client_id",
    "debrid_rd_client_secret",
    "debrid_rd_expires_at",
    "trakt_client_secret",
    "trakt_access_token",
    "trakt_refresh_token",
    "simkl_access_token",
    "kodi_hosts_json",
)

internal fun isSensitivePreferenceKey(key: String): Boolean {
    if (key in SENSITIVE_PREFERENCE_KEYS) return true
    val lower = key.lowercase()
    return listOf("token", "secret", "password", "api_key", "credential")
        .any { it in lower }
}

class SyncRepositoryImpl(
    private val database: TorveDatabase,
    private val json: Json,
    private val userIdProvider: UserIdProvider,
    private val secretStore: IntegrationSecretStore? = null,
) : SyncRepository {
    private val tolerantJson = Json { ignoreUnknownKeys = true }
    private fun uid(): String = userIdProvider.currentUserId()

    companion object {
        /** Keys that contain credentials/tokens — never exported as preferences.
         *  Credentials are synced separately via [integrationSecrets]. */
        private val SENSITIVE_KEYS = setOf(
            "debrid_api_key",
            "debrid_rd_refresh_token",
            "debrid_rd_client_id",
            "debrid_rd_client_secret",
            "debrid_rd_expires_at",
            "trakt_client_secret",
            "trakt_access_token",
            "trakt_refresh_token",
            "simkl_access_token",
            "kodi_hosts_json",
        )

        /** Heuristic: skip any key that looks credential-like. */
        private fun isSensitive(key: String): Boolean {
            return isSensitivePreferenceKey(key)
        }

        // Security: secrets are excluded from default export (backup, backend sync).
        // For explicit local device-to-device transfer, use exportForLocalTransfer()
        // which includes secrets via LOCAL_TRANSFER_SECRET_KEYS.
        val SYNCABLE_SECRET_KEYS = listOf(
            IntegrationSecretKey.MDBLIST_API_KEY,
            IntegrationSecretKey.OMDB_API_KEY,
        )

        /** Keys included only in explicit local device-to-device transfer. */
        val LOCAL_TRANSFER_SECRET_KEYS = listOf(
            IntegrationSecretKey.TRAKT_TOKENS,
            IntegrationSecretKey.TRAKT_ACCESS_TOKEN,
            IntegrationSecretKey.TRAKT_REFRESH_TOKEN,
            IntegrationSecretKey.TRAKT_CLIENT_SECRET,
            IntegrationSecretKey.SIMKL_ACCESS_TOKEN,
            IntegrationSecretKey.OMDB_API_KEY,
            IntegrationSecretKey.MDBLIST_API_KEY,
            IntegrationSecretKey.JELLYFIN_API_KEY,
            IntegrationSecretKey.PLEX_ACCESS_TOKEN,
            IntegrationSecretKey.DEBRID_API_KEY,
            IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID,
            IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID,
            IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE,
            IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
            IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN,
            IntegrationSecretKey.DEBRID_RD_CLIENT_ID,
            IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET,
            IntegrationSecretKey.CLAUDE_API_KEY,
            IntegrationSecretKey.CHATGPT_API_KEY,
            IntegrationSecretKey.GEMINI_API_KEY,
            IntegrationSecretKey.PERPLEXITY_API_KEY,
            IntegrationSecretKey.DEEPSEEK_API_KEY,
        )
    }

    // ── Export ────────────────────────────────────────────────

    override suspend fun exportSyncPayload(): SyncPayload {
        val queries = database.torveQueries

        val addons = queries.getAllAddons(userId = uid()).executeAsList().map { row ->
            SyncAddon(
                manifestUrl = row.manifest_url,
                isEnabled = row.is_enabled == 1L,
                priority = row.priority.toInt(),
            )
        }

        val preferences = queries.getAllPreferences(userId = uid()).executeAsList()
            .filter { !isSensitive(it.key) }
            .map { SyncPreference(key = it.key, value = it.value_) }

        val progress = queries.getAllProgress(userId = uid()).executeAsList().map { row ->
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

        val playlists = queries.getAllPlaylists(userId = uid()).executeAsList().map { row ->
            SyncPlaylist(
                id = row.id,
                name = row.name,
                url = row.url,
                epgUrl = row.epg_url,
                type = row.type ?: "m3u",
                server = row.server,
                username = row.username,
                password = row.password,
            )
        }

        val favorites = queries.getAllFavorites(userId = uid()).executeAsList().map { row ->
            SyncFavorite(
                channelId = row.channel_id,
                playlistId = row.playlist_id,
                name = row.name,
                groupTitle = row.group_title,
            )
        }

        val secrets = if (secretStore != null) {
            SYNCABLE_SECRET_KEYS.mapNotNull { key ->
                val value = secretStore.get(key) ?: return@mapNotNull null
                SyncIntegrationSecret(key = key.name, value = value)
            }
        } else {
            emptyList()
        }

        return SyncPayload(
            exportedAt = Clock.System.now().toEpochMilliseconds(),
            addons = addons,
            preferences = preferences,
            watchProgress = progress,
            channelPlaylists = playlists,
            channelFavorites = favorites,
            integrationSecrets = secrets,
        )
    }

    override suspend fun exportToJson(): String {
        return json.encodeToString(exportSyncPayload())
    }

    override suspend fun exportForLocalTransfer(): String {
        val base = exportSyncPayload()
        val secrets = if (secretStore != null) {
            LOCAL_TRANSFER_SECRET_KEYS.mapNotNull { key ->
                val value = secretStore.get(key) ?: return@mapNotNull null
                SyncIntegrationSecret(key = key.name, value = value)
            }
        } else {
            emptyList()
        }
        return json.encodeToString(base.copy(integrationSecrets = secrets))
    }

    // ── Import ───────────────────────────────────────────────

    override suspend fun importSyncPayload(payload: SyncPayload): SyncResult {
        val queries = database.torveQueries
        var addonsImported = 0
        var prefsImported = 0
        var progressImported = 0
        var playlistsImported = 0
        var favoritesImported = 0
        var conflicts = 0

        // Addons: insert if new, update enabled/priority if existing
        for (addon in payload.addons) {
            val existing = queries.getAddonByUrl(
                userId = uid(),
                manifestUrl = addon.manifestUrl,
            ).executeAsOneOrNull()
            if (existing == null) {
                // New addon — we only have the URL, not the full manifest.
                // Insert a placeholder that the AddonRepository can refresh later.
                queries.insertAddon(
                    user_id = uid(),
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
                    server_id = null,
                    synced_at = null,
                    installed_from = "sync",
                    config_id = null,
                )
                addonsImported++
            } else {
                queries.updateAddonEnabled(
                    isEnabled = if (addon.isEnabled) 1L else 0L,
                    userId = uid(),
                    manifestUrl = addon.manifestUrl,
                )
                queries.updateAddonPriority(
                    priority = addon.priority.toLong(),
                    userId = uid(),
                    manifestUrl = addon.manifestUrl,
                )
                addonsImported++
            }
        }

        // Preferences: last-write-wins, skip sensitive keys
        for (pref in payload.preferences) {
            if (isSensitive(pref.key)) continue
            val safeValue = sanitizePreferenceValue(pref.key, pref.value)
            queries.setPreference(user_id = uid(), key = pref.key, value_ = safeValue)
            prefsImported++
        }

        // Watch progress: prefer recency, but never let stale progress roll back playback.
        for (wp in payload.watchProgress) {
            val local = queries.getProgress(userId = uid(), mediaId = wp.mediaId).executeAsOneOrNull()
            val resolved = if (local == null) {
                wp
            } else {
                val localUpdatedAt = local.updated_at
                val remoteUpdatedAt = wp.updatedAt
                val localRatio = if (local.duration_ms > 0) {
                    local.position_ms.toDouble() / local.duration_ms.toDouble()
                } else {
                    0.0
                }
                val remoteRatio = if (wp.durationMs > 0) {
                    wp.positionMs.toDouble() / wp.durationMs.toDouble()
                } else {
                    0.0
                }

                val newerRemote = remoteUpdatedAt > localUpdatedAt + 20_000
                val newerLocal = localUpdatedAt > remoteUpdatedAt + 20_000
                val remoteAhead = remoteRatio > localRatio + 0.03 || wp.positionMs > local.position_ms + 20_000
                val localAhead = localRatio > remoteRatio + 0.03 || local.position_ms > wp.positionMs + 20_000

                when {
                    newerLocal && !remoteAhead -> {
                        conflicts++
                        null
                    }
                    newerRemote || remoteAhead -> {
                        wp.copy(
                            durationMs = wp.durationMs.coerceAtLeast(local.duration_ms),
                            updatedAt = maxOf(remoteUpdatedAt, localUpdatedAt),
                        )
                    }
                    localAhead -> {
                        conflicts++
                        null
                    }
                    else -> {
                        wp.copy(
                            mediaType = wp.mediaType.ifBlank { local.media_type },
                            title = wp.title.ifBlank { local.title },
                            posterUrl = wp.posterUrl ?: local.poster_url,
                            backdropUrl = wp.backdropUrl ?: local.backdrop_url,
                            positionMs = maxOf(wp.positionMs, local.position_ms),
                            durationMs = maxOf(wp.durationMs, local.duration_ms),
                            seasonNumber = wp.seasonNumber ?: local.season_number?.toInt(),
                            episodeNumber = wp.episodeNumber ?: local.episode_number?.toInt(),
                            showTitle = wp.showTitle ?: local.show_title,
                            updatedAt = maxOf(remoteUpdatedAt, localUpdatedAt),
                        )
                    }
                }
            }

            if (resolved == null) continue
            queries.upsertProgress(
                user_id = uid(),
                media_id = resolved.mediaId,
                media_type = resolved.mediaType,
                title = resolved.title,
                poster_url = resolved.posterUrl,
                backdrop_url = resolved.backdropUrl,
                position_ms = resolved.positionMs,
                duration_ms = resolved.durationMs,
                season_number = resolved.seasonNumber?.toLong(),
                episode_number = resolved.episodeNumber?.toLong(),
                show_title = resolved.showTitle,
                updated_at = resolved.updatedAt,
            )
            progressImported++
        }
        // Channel playlists: insert if URL not already present (match by URL for m3u, by server+username for xtream)
        val existingPlaylists = queries.getAllPlaylists(userId = uid()).executeAsList()
        val existingPlaylistIdentities = existingPlaylists
            .mapNotNull { row ->
                playlistIdentityFor(
                    type = row.type,
                    url = row.url,
                    server = row.server,
                    username = row.username,
                )
            }
            .toMutableSet()
        for (pl in payload.channelPlaylists) {
            val identity = playlistIdentityFor(
                type = pl.type,
                url = pl.url,
                server = pl.server,
                username = pl.username,
            )
            val isDuplicate = identity != null && identity in existingPlaylistIdentities
            if (isDuplicate) {
                conflicts++
                continue
            }
            queries.insertPlaylist(
                user_id = uid(),
                id = pl.id,
                name = pl.name,
                url = pl.url,
                epg_url = pl.epgUrl,
                channel_count = 0,
                last_updated = null,
                type = pl.type,
                server = pl.server,
                username = pl.username,
                password = pl.password,
            )
            identity?.let(existingPlaylistIdentities::add)
            playlistsImported++
        }

        // Channel favorites: upsert by channelId
        for (fav in payload.channelFavorites) {
            queries.insertFavorite(
                user_id = uid(),
                channel_id = fav.channelId,
                playlist_id = fav.playlistId,
                name = fav.name,
                logo_url = null,
                group_title = fav.groupTitle,
                added_at = Clock.System.now().toEpochMilliseconds(),
            )
            favoritesImported++
        }

        // Integration secrets: write to secure store + mirror keys that services read from prefs.
        // Only keys in LOCAL_TRANSFER_SECRET_KEYS are accepted (used for LAN device-to-device transfer).
        var secretsImported = 0
        if (secretStore != null) {
            for (secret in payload.integrationSecrets) {
                val key = runCatching { IntegrationSecretKey.valueOf(secret.key) }.getOrNull()
                    ?: continue
                if (key !in LOCAL_TRANSFER_SECRET_KEYS) continue
                secretStore.put(key, secret.value)
                secretsImported++
                // OmdbClient reads from preferences table — mirror the key there
                if (key == IntegrationSecretKey.OMDB_API_KEY) {
                    queries.setPreference(user_id = uid(), key = "omdb_api_key", value_ = secret.value)
                }
            }
        }

        return SyncResult(
            addonsImported = addonsImported,
            preferencesImported = prefsImported,
            progressImported = progressImported,
            playlistsImported = playlistsImported,
            favoritesImported = favoritesImported,
            secretsImported = secretsImported,
            conflicts = conflicts,
        )
    }

    override suspend fun importFromJson(json: String): SyncResult {
        val payload = this.json.decodeFromString<SyncPayload>(json)
        val schema = if (payload.schemaVersion > 0) payload.schemaVersion else payload.version
        require(schema == 1) { "Unsupported backup schema version: $schema" }
        return importSyncPayload(payload)
    }

    private fun sanitizePreferenceValue(key: String, raw: String): String {
        return when (key) {
            SettingsViewModel.KEY_RATING_PREFS -> {
                val parsed = runCatching {
                    tolerantJson.decodeFromString<RatingDisplayPrefs>(raw)
                }.getOrNull()
                json.encodeToString(parsed?.let(::sanitizeRatingPrefs) ?: RatingDisplayPrefs())
            }
            SettingsViewModel.KEY_CARD_STYLE_PRESETS -> {
                val parsed = runCatching {
                    tolerantJson.decodeFromString<List<CardStylePreset>>(raw)
                }.getOrNull().orEmpty()
                json.encodeToString(sanitizeCardStylePresets(parsed))
            }
            "home_section_configs" -> {
                val parsed = runCatching {
                    tolerantJson.decodeFromString<List<HomeSectionConfig>>(raw)
                }.getOrNull() ?: emptyList()
                json.encodeToString(sanitizeHomeSectionConfigs(parsed))
            }
            else -> raw
        }
    }

    private fun sanitizeRatingPrefs(prefs: RatingDisplayPrefs): RatingDisplayPrefs {
        val allSources = RatingSource.entries
        val enabled = prefs.enabledProviders
            .filter { it in allSources }
            .distinct()
        val ordered = (prefs.providerOrder.filter { it in allSources } + allSources)
            .distinct()
        val torveWeights = (defaultTorveWeights() + prefs.torveWeights)
            .filterKeys { it in allSources && it != RatingSource.TORVE }
            .mapValues { (_, weight) -> weight.coerceIn(0, 100) }

        return prefs.copy(
            enabledProviders = enabled,
            providerOrder = ordered,
            maxRatingsOnCard = prefs.maxRatingsOnCard.coerceIn(1, 9),
            torveWeights = torveWeights,
        )
    }

    private fun sanitizeCardStylePresets(presets: List<CardStylePreset>): List<CardStylePreset> {
        val now = Clock.System.now().toEpochMilliseconds()
        if (presets.isEmpty()) {
            return listOf(
                CardStylePreset(
                    presetId = "default",
                    name = "Default",
                    cardStyle = CardStyle(),
                    isBuiltIn = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        val seen = mutableSetOf<String>()
        return presets.mapIndexed { index, preset ->
            val baseId = preset.presetId.trim().ifBlank { "preset_${index + 1}" }
            var finalId = baseId
            var suffix = 2
            while (!seen.add(finalId)) {
                finalId = "${baseId}_$suffix"
                suffix++
            }
            val safeName = preset.name.trim().ifBlank { "Preset ${index + 1}" }
            val safeStyle = sanitizeCardStyle(preset.cardStyle)
            val createdAt = if (preset.createdAt <= 0L) now else preset.createdAt
            val updatedAt = if (preset.updatedAt <= 0L) createdAt else preset.updatedAt
            preset.copy(
                presetId = finalId,
                name = safeName,
                cardStyle = safeStyle,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }

    private fun sanitizeCardStyle(style: CardStyle): CardStyle {
        val safeSize = style.size.copy(
            preset = style.size.preset.takeIf { it in CardSizePreset.entries } ?: CardSizePreset.M,
            orientation = style.size.orientation.takeIf { it in CardOrientation.entries } ?: CardOrientation.PORTRAIT,
            customWidthDp = style.size.customWidthDp.coerceIn(80, 320),
        )
        val safeHover = style.hover.copy(
            scalePercent = style.hover.scalePercent.coerceIn(100, 130),
            animationDurationMs = style.hover.animationDurationMs.coerceIn(80, 600),
        )
        val safeWatched = style.watched.copy(
            dimAmount = style.watched.dimAmount.coerceIn(0f, 1f),
        )
        val safeAppearance = style.appearance.copy(
            cornerRadiusDp = style.appearance.cornerRadiusDp.coerceIn(0, 32),
            cardSpacingDp = style.appearance.cardSpacingDp.coerceIn(0, 40),
            cardElevationDp = style.appearance.cardElevationDp.coerceIn(0, 16),
        )

        return style.copy(
            size = safeSize,
            hover = safeHover,
            watched = safeWatched,
            appearance = safeAppearance,
            ratingPrefs = sanitizeRatingPrefs(style.ratingPrefs),
        )
    }

    private fun sanitizeHomeSectionConfigs(configs: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val seen = mutableSetOf<com.torve.domain.model.HomeSection>()
        return configs.asSequence()
            .filter { seen.add(it.section) }
            .map { config ->
                config.copy(
                    order = config.order.coerceAtLeast(0),
                    customTitle = config.customTitle?.trim()?.takeIf { it.isNotEmpty() },
                    presetId = config.presetId?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            .toList()
    }
}
