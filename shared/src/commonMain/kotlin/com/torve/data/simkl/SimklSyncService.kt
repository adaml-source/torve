package com.torve.data.simkl

import com.torve.data.auth.UserIdProvider
import com.torve.db.TorveDatabase
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.model.MediaType
import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsRefreshNotifier
import com.torve.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class SimklSyncState(val running: Boolean = false, val lastSyncedAt: Long? = null,
    val error: String? = null, val importedEpisodes: Int = 0, val unresolvedTitles: Int = 0, val personalDataUserId: String? = null)

/** Event-driven, account/profile-bound import. No background polling loop. */
class SimklSyncService(
    private val client: SimklClient,
    private val database: TorveDatabase,
    private val preferences: PreferencesRepository,
    private val secrets: IntegrationSecretStore,
    private val userIds: UserIdProvider,
    private val notifier: SettingsRefreshNotifier,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val _state = MutableStateFlow(SimklSyncState())
    val state = _state.asStateFlow()
    private val lastAttempts = mutableMapOf<String, Long>()

    suspend fun sync(force: Boolean = false): Boolean = withContext(ioDispatcher +
        com.torve.data.auth.capturePersonalDataScope(userIds)) { mutex.withLock {
        val token = secrets.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)?.takeIf { it.isNotBlank() } ?: return@withLock false
        val scope = userIds.currentDataUserIdOrNull() ?: return@withLock false
        if (_state.value.personalDataUserId != scope) _state.value = SimklSyncState(personalDataUserId = scope)
        val now = clock.now().toEpochMilliseconds()
        val mirror = secrets.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, "library_sync_owner")
        if (!force && mirror == token && now - (lastAttempts[scope] ?: 0) < 60_000L) return@withLock true
        lastAttempts[scope] = now
        _state.value = _state.value.copy(running = true, error = null)
        try {
            check(client.flushPendingWrites(token)) { "SIMKL changes are saved on this device and will sync when the connection is available." }
            val cached = if (mirror == token) preferences.getString(CACHE_KEY)
                ?.let { runCatching { json.decodeFromString<SimklLibraryCache>(it) }.getOrNull() } else null
            val activities = client.getActivities(token)
            val all = activities["all"]?.jsonPrimitive?.contentOrNull
                ?: error("SIMKL did not return a sync snapshot. Try again.")
            if (scope != userIds.currentDataUserId() || token != secrets.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)) return@withLock false
            if (cached != null && cached.activities["all"] == activities["all"]) {
                _state.value = _state.value.copy(running = false, lastSyncedAt = now)
                return@withLock true
            }
            val since = cached?.activities?.get("all")?.jsonPrimitive?.contentOrNull
            val merged = cached?.items?.associateBy { it.key }?.toMutableMap() ?: mutableMapOf()
            for (type in listOf("shows", "movies", "anime")) {
                val bucket = if (type == "shows") "tv_shows" else type
                if (cached != null && activities[bucket] == cached.activities[bucket]) continue
                val slice = try { client.getLibrary(token, type, since).items(type) }
                catch (e: SimklApiException) {
                    if (e.statusCode != 400 || e.code != "max_items") throw e
                    val statuses = if (type == "movies") listOf("plantowatch", "completed", "dropped")
                        else listOf("watching", "plantowatch", "hold", "completed", "dropped")
                    statuses.flatMap { client.getLibrary(token, type, since, it).items(type) }
                }
                slice.filter { it.simklId != null }.forEach { merged[it.key] = it }
                if (cached != null && simklRemovedChanged(cached.activities, activities, type)) {
                    val live = client.getLibrary(token, type, idsOnly = true).items(type).mapNotNull {
                        check(it.simklId != null) { "SIMKL returned an incomplete library index. Try again." }; it.key
                    }.toSet()
                    merged.entries.removeAll { it.value.kind == type && it.key !in live }
                }
            }
            // Check both the person and the connection after network I/O, before any local write.
            if (scope != userIds.currentDataUserId() || token != secrets.get(IntegrationSecretKey.SIMKL_ACCESS_TOKEN)) {
                _state.value = _state.value.copy(running = false)
                return@withLock false
            }
            val imported = applyLibrary(scope, merged.values.toList(), cached?.items.orEmpty())
            preferences.setString(CACHE_KEY, json.encodeToString(SimklLibraryCache(activities, merged.values.toList())))
            secrets.put(IntegrationSecretKey.SIMKL_ACCESS_TOKEN, token, "library_sync_owner")
            _state.value = SimklSyncState(lastSyncedAt = now, importedEpisodes = imported,
                unresolvedTitles = merged.values.count { it.toMedia() == null }, personalDataUserId = scope)
            notifier.notifyRefresh(now)
            true
        } catch (e: Exception) {
            if (e is CancellationException) { _state.value = _state.value.copy(running = false); throw e }
            _state.value = _state.value.copy(running = false, error = e.message ?: "SIMKL sync failed. Try again.")
            false
        } finally {
            if (_state.value.personalDataUserId == scope) _state.value = if (scope == userIds.currentDataUserId()) _state.value.copy(running = false)
                else SimklSyncState(personalDataUserId = userIds.currentDataUserId())
        }
    } }

    private fun applyLibrary(scope: String, items: List<SimklLibraryItem>, previous: List<SimklLibraryItem>): Int {
        val queries = database.torveQueries
        var episodes = 0
        database.transaction {
            val currentWatched = items.flatMap { item -> item.watchedEpisodes().map { ep ->
                Triple(item.toMedia()?.id, ep.season to ep.episode, ep.watchedAtMs)
            } }.toSet()
            previous.forEach { item ->
                val mediaId = item.toMedia()?.id ?: return@forEach
                val previousLast = item.watchedEpisodes().maxByOrNull { it.watchedAtMs } ?: return@forEach
                val local = queries.getProgress(scope, mediaId).executeAsOneOrNull()
                if (Triple(mediaId, previousLast.season to previousLast.episode, previousLast.watchedAtMs) !in currentWatched &&
                    local?.updated_at == previousLast.watchedAtMs && local.season_number == previousLast.season.toLong() &&
                    local.episode_number == previousLast.episode.toLong() && local.position_ms >= local.duration_ms)
                    queries.deleteProgressByMediaId(scope, mediaId)
            }
            // Reconcile only rows this importer owns; never delete local or Trakt history.
            queries.getAllHistory(scope).executeAsList().filter { it.id.startsWith("simkl_") }.forEach {
                queries.deleteHistory(scope, it.id)
            }
            val previousPlanned = previous.filter { it.status == "plantowatch" }.mapNotNull { it.toMedia()?.id }.toSet()
            val currentPlanned = items.filter { it.status == "plantowatch" }.mapNotNull { it.toMedia()?.id }.toSet()
            for (removed in previousPlanned - currentPlanned) {
                val row = queries.getAllWatchlist(scope).executeAsList().firstOrNull { it.media_id == removed }
                // Negative sort_order identifies a SIMKL-owned addition; existing local entries stay local.
                if (row?.sort_order == -2L) queries.removeFromWatchlist(scope, removed)
            }
            items.forEach { item ->
                val media = item.toMedia() ?: return@forEach
                val type = if (media.type == MediaType.MOVIE) "movie" else "series"
                if (item.status == "plantowatch" && queries.isInWatchlist(scope, media.id).executeAsOne() == 0L) {
                    queries.insertWatchlistItem(scope, media.id, type, media.tmdbId!!.toLong(), media.imdbId,
                        media.title, media.posterUrl, null, null, media.year?.toLong(), null,
                        item.addedAt?.let(::simklTimestampMs) ?: 0L, -2L)
                }
                if (media.type == MediaType.MOVIE && item.status == "completed") {
                    val at = item.lastWatchedAt?.let(::simklTimestampMs) ?: return@forEach
                    queries.insertHistory(scope, "simkl_${item.key}", media.id, type, media.title,
                        media.posterUrl, null, at, 0, null, null, null)
                } else if (media.type == MediaType.SERIES) {
                    val watched = item.watchedEpisodes()
                    watched.forEach { ep ->
                        episodes++
                        queries.insertHistory(scope, "simkl_${item.key}_${ep.season}_${ep.episode}", media.id,
                            type, "${media.title} · S${ep.season} E${ep.episode}", media.posterUrl, null,
                            ep.watchedAtMs, 0, ep.season.toLong(), ep.episode.toLong(), media.title)
                    }
                    val last = watched.maxByOrNull { it.watchedAtMs }
                    val local = queries.getProgress(scope, media.id).executeAsOneOrNull()
                    // A manual watched mark can be newer than a real playback bookmark.
                    // Keep unfinished playback so removing that mark restores its resume.
                    val unfinishedPlayback = local != null && local.position_ms > 0 && local.duration_ms > 0 &&
                        local.position_ms.toDouble() / local.duration_ms < .85
                    if (last != null && !unfinishedPlayback && (local == null || local.updated_at < last.watchedAtMs)) {
                        val duration = (last.runtime ?: media.runtime ?: 45).coerceAtLeast(1) * 60_000L
                        queries.upsertProgress(scope, media.id, type, media.title, media.posterUrl, null,
                            duration, duration, last.season.toLong(), last.episode.toLong(), media.title, last.watchedAtMs)
                    }
                }
            }
        }
        return episodes
    }

    companion object { const val CACHE_KEY = "simkl_library_cache_v1" }
}
