package com.torve.data.trakt.api

import com.torve.data.trakt.TraktCalendarEpisode
import com.torve.domain.repository.PreferencesRepository
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class SavedTraktCalendar(
    val scope: String,
    val days: Int,
    val loadedAtMs: Long,
    val episodes: List<TraktCalendarEpisode>,
)

/** No tokens or credentials. The opaque connection scope also changes on reconnect. */
internal class TraktCalendarSnapshotStore(private val prefs: PreferencesRepository) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun read(scope: String, days: Int, now: Long): SavedTraktCalendar? = try {
        withContext(ioDispatcher) {
            prefs.getString(key(days))?.let { json.decodeFromString<SavedTraktCalendar>(it) }
                ?.takeIf { it.scope == scope && it.days == days && now - it.loadedAtMs in 0..MAX_AGE_MS }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    suspend fun write(value: SavedTraktCalendar) {
        try { withContext(ioDispatcher) { prefs.setString(key(value.days), json.encodeToString(value)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the live calendar usable if local storage is unavailable. */ }
    }

    private fun key(days: Int) = "trakt_calendar_snapshot_v1:$days"
    companion object { const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L }
}
