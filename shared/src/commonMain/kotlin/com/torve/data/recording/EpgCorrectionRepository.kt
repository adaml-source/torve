package com.torve.data.recording

import com.torve.domain.recording.EpgCorrection
import com.torve.domain.repository.PreferencesRepository
import kotlinx.serialization.json.Json

/**
 * Per-playlist EPG correction state, persisted as JSON in
 * [PreferencesRepository] under `epg_correction_<playlistId>`.
 *
 * Plain prefs (not a SQLDelight table) so the schema stays simple and
 * sign-out's "wipe prefs" hook handles cleanup for free. Corrections
 * are bounded — a few KB per playlist at worst — so JSON in a key/value
 * store is the right tradeoff.
 */
class EpgCorrectionRepository(
    private val prefs: PreferencesRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {

    suspend fun get(playlistId: String): EpgCorrection {
        val raw = runCatching { prefs.getString(keyFor(playlistId)) }.getOrNull()
            ?: return empty(playlistId)
        if (raw.isBlank()) return empty(playlistId)
        return runCatching { json.decodeFromString(EpgCorrection.serializer(), raw) }
            .getOrElse { empty(playlistId) }
    }

    suspend fun save(correction: EpgCorrection) {
        if (correction.isEmpty) {
            // Drop the key when the user reverts everything — keeps the
            // prefs DB tidy and avoids stale rows after a wipe-and-reset.
            runCatching { prefs.remove(keyFor(correction.playlistId)) }
            return
        }
        val encoded = runCatching { json.encodeToString(EpgCorrection.serializer(), correction) }
            .getOrNull() ?: return
        runCatching { prefs.setString(keyFor(correction.playlistId), encoded) }
    }

    /** Convenience wrappers for the three correction levers. */

    suspend fun setOffsetMinutes(playlistId: String, offsetMinutes: Int) {
        save(get(playlistId).copy(offsetMinutes = offsetMinutes))
    }

    suspend fun setTvgIdMapping(playlistId: String, channelId: String, epgChannelId: String?) {
        val current = get(playlistId)
        val updated = current.copy(
            tvgIdRemap = current.tvgIdRemap.toMutableMap().apply {
                if (epgChannelId.isNullOrBlank() || epgChannelId == channelId) {
                    remove(channelId)
                } else {
                    put(channelId, epgChannelId)
                }
            },
        )
        save(updated)
    }

    suspend fun setHiddenCategories(playlistId: String, categories: Set<String>) {
        save(get(playlistId).copy(hiddenCategories = categories))
    }

    private fun empty(playlistId: String): EpgCorrection = EpgCorrection(playlistId = playlistId)

    private fun keyFor(playlistId: String): String = KEY_PREFIX + playlistId

    companion object {
        const val KEY_PREFIX: String = "epg_correction_"
    }
}
