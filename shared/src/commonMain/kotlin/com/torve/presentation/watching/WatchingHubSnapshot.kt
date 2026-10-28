package com.torve.presentation.watching

import com.torve.domain.repository.PreferencesRepository
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Presentation data only. Stored locally in the existing account/profile namespace. */
@Serializable
internal data class WatchingHubSnapshot(
    val person: String,
    val savedAtMs: Long,
    val upNext: List<WatchingEpisode>,
    val calendar: List<WatchingEpisode>,
)

internal class WatchingHubSnapshotStore(
    private val prefs: PreferencesRepository,
    private val currentPerson: () -> String?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): WatchingHubSnapshot? {
        val person = currentPerson() ?: return null
        val saved = try {
            withContext(ioDispatcher) {
                prefs.getString(KEY)?.let { json.decodeFromString<WatchingHubSnapshot>(it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } ?: return null
        if (currentPerson() != person || saved.person != person) return null
        val age = now() - saved.savedAtMs
        return saved.takeIf { age in 0..MAX_AGE_MS }
    }

    suspend fun write(state: WatchingHubState) {
        val person = currentPerson() ?: return
        if (state.personalDataUserId != person) return
        // Cards do not need the entire cast and every season's episodes on disk.
        fun WatchingEpisode.card() = copy(show = show.copy(seasons = emptyList(), cast = emptyList()))
        val snapshot = WatchingHubSnapshot(person, now(), state.upNext.take(300).map { it.card() },
            state.calendar.take(1000).map { it.card() })
        try {
            withContext(ioDispatcher) {
                val encoded = json.encodeToString(snapshot)
                if (currentPerson() == person) prefs.setString(KEY, encoded)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Cache writes never delay or fail the visible library. */ }
    }

    companion object {
        const val KEY = "watching_hub_snapshot_v1"
        const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
