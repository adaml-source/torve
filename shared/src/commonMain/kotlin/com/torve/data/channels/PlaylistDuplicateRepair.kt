package com.torve.data.channels

import com.torve.db.Iptv_playlist
import com.torve.db.TorveDatabase
import com.torve.platform.torveVerboseLog
import kotlinx.datetime.Clock

private const val KEY_CHANNELS_SELECTED_PLAYLIST = "channels_selected_playlist"
private const val KEY_CHANNELS_SELECTED_GROUP_PREFIX = "channels_selected_group_"
private const val KEY_CHANNELS_SELECTED_CHANNEL_PREFIX = "channels_selected_channel_"
private const val KEY_CHANNELS_LAST_WATCHED_CHANNEL_PREFIX = "channels_last_watched_channel_"
private const val PREF_EPG_LOAD_STATE_PREFIX = "epg_load_state_"
private const val PREF_EPG_ACTIVE_GENERATION_PREFIX = "epg_active_generation_"
private const val PREF_CHANNEL_ACTIVE_GENERATION_PREFIX = "iptv_channel_active_generation_"
private const val PREF_CHANNEL_LAST_SYNC_PREFIX = "channel_last_sync_"
private const val PREF_CHANNEL_STAGED_GENERATION_PREFIX = "channel_staged_generation_"

internal data class DuplicatePlaylistRepairResult(
    val mergedGroups: Int = 0,
    val removedPlaylistIds: Set<String> = emptySet(),
    val touchedPlaylistIds: Set<String> = emptySet(),
)

private data class DuplicateRepairCandidate(
    val row: Iptv_playlist,
    val channelGeneration: Long?,
    val epgGeneration: Long?,
    val channelCount: Long,
    val epgProgrammeCount: Long,
)

internal fun duplicatePlaylistGroups(
    rows: List<Iptv_playlist>,
): Map<PlaylistIdentity, List<Iptv_playlist>> {
    return rows
        .mapNotNull { row ->
            playlistIdentityFor(
                type = row.type,
                url = row.url,
                server = row.server,
                username = row.username,
            )?.let { identity -> identity to row }
        }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
}

internal suspend fun repairDuplicatePlaylistsForUser(
    database: TorveDatabase,
    userId: String,
    loadXtreamPassword: suspend (String) -> String?,
    saveXtreamPassword: suspend (String, String) -> Unit,
    removeXtreamPassword: suspend (String) -> Unit,
): DuplicatePlaylistRepairResult {
    val rows = database.torveQueries.getAllPlaylists(userId = userId).executeAsList()
    val groups = duplicatePlaylistGroups(rows)
    if (groups.isEmpty()) return DuplicatePlaylistRepairResult()

    val xtreamPasswords = mutableMapOf<String, String?>()
    groups.values.flatten()
        .filter { it.type.equals("xtream", ignoreCase = true) }
        .forEach { row ->
            xtreamPasswords[row.id] = loadXtreamPassword(row.id)
        }

    val removed = linkedSetOf<String>()
    val touched = linkedSetOf<String>()
    val passwordCopies = mutableListOf<Pair<String, String>>()

    database.transaction {
        groups.values.forEach { groupRows ->
            val candidates = groupRows.map { row -> database.repairCandidate(userId, row) }
            val selectedId = database.torveQueries
                .getPreference(userId = userId, key = KEY_CHANNELS_SELECTED_PLAYLIST)
                .executeAsOneOrNull()
            val winner = chooseDuplicateWinner(candidates, selectedId)
            val losers = candidates.filterNot { it.row.id == winner.row.id }
            if (losers.isEmpty()) return@forEach

            val strongestCatalog = candidates.maxWithOrNull(
                compareBy<DuplicateRepairCandidate> { it.channelCount }
                    .thenBy { it.row.last_updated ?: Long.MIN_VALUE }
                    .thenByDescending { it.row.id },
            ) ?: winner
            val copiedChannelCount = if (
                strongestCatalog.row.id != winner.row.id &&
                strongestCatalog.channelGeneration != null &&
                strongestCatalog.channelCount > winner.channelCount
            ) {
                database.copyChannelSnapshot(
                    userId = userId,
                    source = strongestCatalog,
                    winner = winner,
                )
            } else {
                winner.channelCount
            }

            val strongestEpg = candidates.maxWithOrNull(
                compareBy<DuplicateRepairCandidate> { it.epgProgrammeCount }
                    .thenBy { it.row.last_updated ?: Long.MIN_VALUE }
                    .thenByDescending { it.row.id },
            ) ?: winner
            if (
                strongestEpg.row.id != winner.row.id &&
                strongestEpg.epgGeneration != null &&
                strongestEpg.epgProgrammeCount > winner.epgProgrammeCount
            ) {
                database.copyEpgSnapshot(
                    userId = userId,
                    source = strongestEpg,
                    winner = winner,
                )
            }

            mergeWinnerPlaylistMetadata(
                database = database,
                userId = userId,
                winner = winner.row,
                groupRows = groupRows,
                channelCount = maxOf(copiedChannelCount, winner.channelCount).coerceAtMost(Int.MAX_VALUE.toLong()),
            )

            losers.forEach { loser ->
                val selectedWasLoser = selectedId == loser.row.id
                database.mergePlaylistScopedState(
                    userId = userId,
                    winnerId = winner.row.id,
                    loserId = loser.row.id,
                    selectedWasLoser = selectedWasLoser,
                )
                database.deleteDuplicatePlaylistRows(userId, loser.row.id)
                removed += loser.row.id
                touched += loser.row.id
            }
            touched += winner.row.id

            if (winner.row.type.equals("xtream", ignoreCase = true)) {
                val winnerPassword = xtreamPasswords[winner.row.id]
                if (winnerPassword.isNullOrBlank()) {
                    losers.firstNotNullOfOrNull { loser -> xtreamPasswords[loser.row.id]?.takeIf { it.isNotBlank() } }
                        ?.let { password -> passwordCopies += winner.row.id to password }
                }
            }
        }
    }

    passwordCopies.distinctBy { it.first }.forEach { (winnerId, password) ->
        saveXtreamPassword(winnerId, password)
    }
    removed.forEach { removeXtreamPassword(it) }

    torveVerboseLog {
        "PlaylistRepair: mergedGroups=${groups.size} removed=${removed.size} touched=${touched.size}"
    }
    return DuplicatePlaylistRepairResult(
        mergedGroups = groups.size,
        removedPlaylistIds = removed,
        touchedPlaylistIds = touched,
    )
}

private fun TorveDatabase.repairCandidate(
    userId: String,
    row: Iptv_playlist,
): DuplicateRepairCandidate {
    val channelGeneration = generationPref(userId, PREF_CHANNEL_ACTIVE_GENERATION_PREFIX, row.id)
    val epgGeneration = generationPref(userId, PREF_EPG_ACTIVE_GENERATION_PREFIX, row.id)
    val channelCount = channelGeneration
        ?.let { generation ->
            torveQueries.countChannelsForPlaylistGeneration(
                userId = userId,
                playlistId = row.id,
                generationId = generation,
            ).executeAsOne()
        }
        ?: row.channel_count
    val epgCount = epgGeneration
        ?.let { generation ->
            torveQueries.countEpgProgrammesForPlaylistGeneration(
                userId = userId,
                playlistId = row.id,
                generationId = generation,
            ).executeAsOne()
        }
        ?: 0L
    return DuplicateRepairCandidate(
        row = row,
        channelGeneration = channelGeneration,
        epgGeneration = epgGeneration,
        channelCount = channelCount,
        epgProgrammeCount = epgCount,
    )
}

private fun chooseDuplicateWinner(
    candidates: List<DuplicateRepairCandidate>,
    selectedId: String?,
): DuplicateRepairCandidate {
    return candidates.maxWith(
        compareBy<DuplicateRepairCandidate> { if (it.row.id == selectedId) 1 else 0 }
            .thenBy { it.channelCount }
            .thenBy { if (!it.row.epg_url.isNullOrBlank()) 1 else 0 }
            .thenBy { it.epgProgrammeCount }
            .thenBy { it.row.last_updated ?: Long.MIN_VALUE }
            .thenByDescending { it.row.id },
    )
}

private fun mergeWinnerPlaylistMetadata(
    database: TorveDatabase,
    userId: String,
    winner: Iptv_playlist,
    groupRows: List<Iptv_playlist>,
    channelCount: Long,
) {
    val epgUrl = winner.epg_url?.takeIf { it.isNotBlank() }
        ?: groupRows.firstNotNullOfOrNull { it.epg_url?.takeIf(String::isNotBlank) }
    val lastUpdated = groupRows.mapNotNull { it.last_updated }.maxOrNull() ?: winner.last_updated
    database.torveQueries.insertPlaylist(
        user_id = userId,
        id = winner.id,
        name = winner.name,
        url = winner.url,
        epg_url = epgUrl,
        channel_count = channelCount,
        last_updated = lastUpdated,
        type = winner.type,
        server = winner.server,
        username = winner.username,
        password = null,
    )
}

private fun TorveDatabase.copyChannelSnapshot(
    userId: String,
    source: DuplicateRepairCandidate,
    winner: DuplicateRepairCandidate,
): Long {
    val sourceGeneration = source.channelGeneration ?: return winner.channelCount
    val now = Clock.System.now().toEpochMilliseconds()
    val winnerGeneration = nextChannelSnapshotGeneration(
        updatedAt = now,
        activeGeneration = generationPref(userId, PREF_CHANNEL_ACTIVE_GENERATION_PREFIX, winner.row.id),
    )
    val rows = torveQueries.getChannelsForPlaylistGeneration(
        userId = userId,
        playlistId = source.row.id,
        generationId = sourceGeneration,
    ).executeAsList()

    rows.forEachIndexed { index, row ->
        torveQueries.insertChannel(
            user_id = userId,
            playlist_id = winner.row.id,
            generation_id = winnerGeneration,
            stable_id = rewritePlaylistScopedId(row.stable_id, source.row.id, winner.row.id),
            sort_index = index.toLong(),
            name = row.name,
            stream_url = row.stream_url,
            tvg_id = row.tvg_id,
            tvg_name = row.tvg_name,
            logo_url = row.logo_url,
            group_title = row.group_title,
            tvg_language = row.tvg_language,
            tvg_country = row.tvg_country,
            tvg_shift = row.tvg_shift,
            channel_number = row.channel_number,
            duration = row.duration,
            catchup_type = row.catchup_type,
            catchup_days = row.catchup_days,
            catchup_source = row.catchup_source,
            user_agent = row.user_agent,
            vlc_options = row.vlc_options,
            kodi_props = row.kodi_props,
            content_type = row.content_type,
            updated_at = now,
        )
    }
    torveQueries.setPreference(
        user_id = userId,
        key = prefKey(PREF_CHANNEL_ACTIVE_GENERATION_PREFIX, winner.row.id),
        value_ = winnerGeneration.toString(),
    )
    torveQueries.setPreference(
        user_id = userId,
        key = prefKey(PREF_CHANNEL_LAST_SYNC_PREFIX, winner.row.id),
        value_ = now.toString(),
    )
    torveQueries.deletePreference(
        userId = userId,
        key = prefKey(PREF_CHANNEL_STAGED_GENERATION_PREFIX, winner.row.id),
    )
    return rows.size.toLong()
}

private fun TorveDatabase.copyEpgSnapshot(
    userId: String,
    source: DuplicateRepairCandidate,
    winner: DuplicateRepairCandidate,
) {
    val sourceGeneration = source.epgGeneration ?: return
    val now = Clock.System.now().toEpochMilliseconds()
    val winnerGeneration = nextChannelSnapshotGeneration(
        updatedAt = now,
        activeGeneration = generationPref(userId, PREF_EPG_ACTIVE_GENERATION_PREFIX, winner.row.id),
    )
    torveQueries.getAllEpgChannelRowsForPlaylistGeneration(
        userId = userId,
        playlistId = source.row.id,
        generationId = sourceGeneration,
    ).executeAsList().forEach { row ->
        torveQueries.insertEpgChannel(
            user_id = userId,
            playlist_id = winner.row.id,
            generation_id = winnerGeneration,
            channel_id = rewritePlaylistScopedId(row.channel_id, source.row.id, winner.row.id),
            epg_channel_key = rewritePlaylistScopedId(row.epg_channel_key, source.row.id, winner.row.id),
            xmltv_channel_id = row.xmltv_channel_id,
            display_name = row.display_name,
            icon_url = row.icon_url,
            updated_at = now,
        )
    }
    torveQueries.getAllEpgProgrammeRowsForPlaylistGeneration(
        userId = userId,
        playlistId = source.row.id,
        generationId = sourceGeneration,
    ).executeAsList().forEach { row ->
        torveQueries.insertEpgProgramme(
            user_id = userId,
            playlist_id = winner.row.id,
            generation_id = winnerGeneration,
            channel_id = rewritePlaylistScopedId(row.channel_id, source.row.id, winner.row.id),
            epg_channel_key = rewritePlaylistScopedId(row.epg_channel_key, source.row.id, winner.row.id),
            xmltv_channel_id = row.xmltv_channel_id,
            start_time = row.start_time,
            end_time = row.end_time,
            title = row.title,
        )
    }
    torveQueries.setPreference(
        user_id = userId,
        key = prefKey(PREF_EPG_ACTIVE_GENERATION_PREFIX, winner.row.id),
        value_ = winnerGeneration.toString(),
    )
    torveQueries.setPreference(
        user_id = userId,
        key = prefKey(PREF_EPG_LOAD_STATE_PREFIX, winner.row.id),
        value_ = "READY",
    )
}

private fun TorveDatabase.mergePlaylistScopedState(
    userId: String,
    winnerId: String,
    loserId: String,
    selectedWasLoser: Boolean,
) {
    if (selectedWasLoser) {
        torveQueries.setPreference(
            user_id = userId,
            key = KEY_CHANNELS_SELECTED_PLAYLIST,
            value_ = winnerId,
        )
    }
    copyPreference(
        userId = userId,
        loserKey = prefKey(KEY_CHANNELS_SELECTED_GROUP_PREFIX, loserId),
        winnerKey = prefKey(KEY_CHANNELS_SELECTED_GROUP_PREFIX, winnerId),
        overwrite = selectedWasLoser,
    )
    copyPreference(
        userId = userId,
        loserKey = prefKey(KEY_CHANNELS_SELECTED_CHANNEL_PREFIX, loserId),
        winnerKey = prefKey(KEY_CHANNELS_SELECTED_CHANNEL_PREFIX, winnerId),
        overwrite = selectedWasLoser,
        transform = { rewritePlaylistScopedId(it, loserId, winnerId) },
    )
    copyPreference(
        userId = userId,
        loserKey = prefKey(KEY_CHANNELS_LAST_WATCHED_CHANNEL_PREFIX, loserId),
        winnerKey = prefKey(KEY_CHANNELS_LAST_WATCHED_CHANNEL_PREFIX, winnerId),
        overwrite = false,
        transform = { rewritePlaylistScopedId(it, loserId, winnerId) },
    )

    val winnerCategories = torveQueries.getCategoryConfigs(userId = userId, playlistId = winnerId)
        .executeAsList()
        .map { it.category_name }
        .toMutableSet()
    torveQueries.getCategoryConfigs(userId = userId, playlistId = loserId)
        .executeAsList()
        .forEach { row ->
            if (winnerCategories.add(row.category_name)) {
                torveQueries.upsertCategoryConfig(
                    user_id = userId,
                    playlist_id = winnerId,
                    category_name = row.category_name,
                    is_visible = row.is_visible,
                    sort_order = row.sort_order,
                )
            }
        }

    torveQueries.getAllFavorites(userId = userId)
        .executeAsList()
        .filter { it.playlist_id == loserId }
        .forEach { row ->
            torveQueries.insertFavorite(
                user_id = userId,
                channel_id = rewritePlaylistScopedId(row.channel_id, loserId, winnerId),
                playlist_id = winnerId,
                name = row.name,
                logo_url = row.logo_url,
                group_title = row.group_title,
                added_at = row.added_at,
            )
        }
    torveQueries.getAllRecentChannels(userId = userId)
        .executeAsList()
        .filter { it.playlist_id == loserId }
        .forEach { row ->
            torveQueries.insertRecentChannel(
                user_id = userId,
                channel_id = rewritePlaylistScopedId(row.channel_id, loserId, winnerId),
                playlist_id = winnerId,
                name = row.name,
                logo_url = row.logo_url,
                group_title = row.group_title,
                stream_url = row.stream_url,
                viewed_at = row.viewed_at,
            )
        }

    deletePlaylistScopedPreferences(userId, loserId)
}

private fun TorveDatabase.copyPreference(
    userId: String,
    loserKey: String,
    winnerKey: String,
    overwrite: Boolean,
    transform: (String) -> String = { it },
) {
    val loserValue = torveQueries.getPreference(userId = userId, key = loserKey).executeAsOneOrNull()
        ?: return
    val winnerValue = torveQueries.getPreference(userId = userId, key = winnerKey).executeAsOneOrNull()
    if (overwrite || winnerValue == null) {
        torveQueries.setPreference(
            user_id = userId,
            key = winnerKey,
            value_ = transform(loserValue),
        )
    }
}

private fun TorveDatabase.deleteDuplicatePlaylistRows(
    userId: String,
    loserId: String,
) {
    torveQueries.deleteFavoritesForPlaylist(userId = userId, playlistId = loserId)
    torveQueries.deleteRecentChannelsForPlaylist(userId = userId, playlistId = loserId)
    torveQueries.deleteCategoryConfigsForPlaylist(userId = userId, playlistId = loserId)
    torveQueries.deleteEpgProgrammesForPlaylist(userId = userId, playlistId = loserId)
    torveQueries.deleteEpgChannelsForPlaylist(userId = userId, playlistId = loserId)
    torveQueries.deleteChannelsForPlaylist(userId = userId, playlistId = loserId)
    torveQueries.deletePlaylist(userId = userId, playlistId = loserId)
}

private fun TorveDatabase.deletePlaylistScopedPreferences(userId: String, playlistId: String) {
    listOf(
        prefKey(KEY_CHANNELS_SELECTED_GROUP_PREFIX, playlistId),
        prefKey(KEY_CHANNELS_SELECTED_CHANNEL_PREFIX, playlistId),
        prefKey(KEY_CHANNELS_LAST_WATCHED_CHANNEL_PREFIX, playlistId),
        prefKey(PREF_EPG_LOAD_STATE_PREFIX, playlistId),
        prefKey(PREF_EPG_ACTIVE_GENERATION_PREFIX, playlistId),
        prefKey(PREF_CHANNEL_ACTIVE_GENERATION_PREFIX, playlistId),
        prefKey(PREF_CHANNEL_LAST_SYNC_PREFIX, playlistId),
        prefKey(PREF_CHANNEL_STAGED_GENERATION_PREFIX, playlistId),
    ).forEach { key ->
        torveQueries.deletePreference(userId = userId, key = key)
    }
}

private fun TorveDatabase.generationPref(
    userId: String,
    prefix: String,
    playlistId: String,
): Long? {
    return torveQueries.getPreference(
        userId = userId,
        key = prefKey(prefix, playlistId),
    ).executeAsOneOrNull()?.toLongOrNull()
}

private fun prefKey(prefix: String, playlistId: String): String = "$prefix$playlistId"

internal fun rewritePlaylistScopedId(
    value: String,
    loserId: String,
    winnerId: String,
): String {
    val stablePrefix = "$loserId::"
    if (value.startsWith(stablePrefix)) {
        return "$winnerId::${value.removePrefix(stablePrefix)}"
    }
    val legacyPrefix = "${loserId}_"
    if (value.startsWith(legacyPrefix)) {
        return "${winnerId}_${value.removePrefix(legacyPrefix)}"
    }
    return value
}
