package com.torve.presentation.apple

import com.torve.data.auth.*
import com.torve.data.addon.SourceProfile
import com.torve.data.addon.continuationSourceKey
import com.torve.domain.model.*
import com.torve.domain.player.PlaybackViewingTracker
import com.torve.domain.repository.WatchProgressRepository
import com.torve.presentation.detail.DetailUiState
import com.torve.presentation.detail.primaryPlaybackTarget
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.random.Random
import com.torve.data.replays.*
import com.torve.domain.repository.DeviceLocalSettingsRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ApplePlaybackIdentity(val mediaId: String, val title: String, val imdbId: String?, val mediaType: MediaType,
    val season: Int?, val episode: Int?, val continuationToken: String? = null)

internal data class AppleEpisodeContinuation(val identity: ApplePlaybackIdentity, val source: SourceProfile?)

/** Playback identity stays in memory; signed stream URLs never enter saved navigation state. */
class ApplePlaybackSessions(private val ids: UserIdProvider, private val progress: WatchProgressRepository,
    private val family: com.torve.data.profile.FamilyControls? = null, private val replayPrefs: DeviceLocalSettingsRepository? = null) {
    private data class Session(val scope: PersonalDataScope, val template: WatchProgress, val start: Long,
                               val identity: ApplePlaybackIdentity, val sourceState: DetailUiState? = null,
                               var source: SourceProfile? = null,
                               val viewing: PlaybackViewingTracker = PlaybackViewingTracker(0), var restored: Boolean = false,
                               val replay: Boolean = false)
    private data class Handoff(val scope: PersonalDataScope, val request: AppleEpisodeContinuation, val createdAt: Long)
    private val sessions = linkedMapOf<String, Session>()
    private val continuations = linkedMapOf<String, Handoff>()
    private val writeLock = Mutex()
    private val replayJson = Json { ignoreUnknownKeys = true }
    private fun replayProgressKey() = if (ids.isSignedIn()) "web_replays_v1_progress" else "web_replays_guest_web_replays_v1_progress"
    @Throws(Exception::class)
    suspend fun replayProgress(id: String): ReplayProgress? = ids.withPersonalDataScope {
        runCatching { replayJson.decodeFromString<Map<String, ReplayProgress>>(replayPrefs?.getString(replayProgressKey()).orEmpty())[id] }.getOrNull()
    }
    /** Web replays keep their own history; a game never becomes a movie in Trakt. */
    @Throws(Exception::class)
    suspend fun beginReplay(event: ReplayEvent, stream: ResolvedReplay): String? = ids.withPersonalDataScope {
        require(event.id.startsWith("web-replay:") && WebReplayUrls.normalize(stream.url) != null)
        if (family?.allows(event.mediaItem(), "web-replays") == false) return@withPersonalDataScope null
        val saved = replayProgress(event.id)
        val start = ReplayProgressPolicy.start(saved)
        val token = Random.nextLong().toString(16) + Clock.System.now().toEpochMilliseconds().toString(16)
        sessions[token] = Session(PersonalDataScope(ids.currentUserId(), ids.currentProfileId()),
            WatchProgress(event.id, MediaType.MOVIE, event.title, event.artwork, event.artwork), start,
            ApplePlaybackIdentity(event.id, event.title, null, MediaType.MOVIE, null, null), replay = true)
        PendingReplayHeaders.value.stage(ids.currentDataUserId(), stream)
        token
    }
    @Throws(Exception::class)
    suspend fun beginOffline(download: Download): String? = ids.withPersonalDataScope {
        if (family?.allows(MediaItem(id = download.mediaId, type = download.mediaType, title = download.title, familySourceKey = "downloads"), "downloads") == false) return@withPersonalDataScope null
        if (download.status != DownloadStatus.COMPLETED || download.filePath.isNullOrBlank()) return@withPersonalDataScope null
        val saved = progress.getProgress(download.mediaId)?.takeIf {
            it.mediaType == download.mediaType && it.seasonNumber == download.seasonNumber && it.episodeNumber == download.episodeNumber
        }
        val previousCoverage = progress.playbackCoverage(WatchProgress(download.mediaId, download.mediaType, download.title,
            seasonNumber = download.seasonNumber, episodeNumber = download.episodeNumber))
        val token = Random.nextLong().toString(16) + Clock.System.now().toEpochMilliseconds().toString(16)
        sessions[token] = Session(PersonalDataScope(ids.currentUserId(), ids.currentProfileId()),
            WatchProgress(download.mediaId, download.mediaType, download.title, download.posterUrl, null,
                seasonNumber = download.seasonNumber, episodeNumber = download.episodeNumber,
                showTitle = download.title.takeIf { download.mediaType == MediaType.SERIES }),
            saved?.positionMs?.takeIf { !saved.isWatched() } ?: 0,
            ApplePlaybackIdentity(download.mediaId, download.title, null, download.mediaType, download.seasonNumber, download.episodeNumber),
            viewing = PlaybackViewingTracker(previousCoverage?.expectedRuntimeMs ?: 0))
        while (sessions.size > 4) sessions.remove(sessions.keys.first())
        token
    }
    fun begin(state: DetailUiState): String? {
        val item = state.mediaItem ?: return null
        if (item.catalogueNotice != null || family?.allows(item) == false) return null
        val target = state.primaryPlaybackTarget
        if (item.type == MediaType.SERIES && (target.season == null || target.episode == null)) return null
        val token = Random.nextLong().toString(16) + Clock.System.now().toEpochMilliseconds().toString(16)
        val scope = PersonalDataScope(ids.currentUserId(), ids.currentProfileId())
        sessions[token] = Session(scope, WatchProgress(item.id, item.type, item.title,
            item.posterUrl, item.backdropUrl, seasonNumber = target.season, episodeNumber = target.episode,
            showTitle = item.title.takeIf { item.type == MediaType.SERIES }), target.positionMs ?: 0,
            ApplePlaybackIdentity(item.id, item.title, item.imdbId, item.type, target.season, target.episode), state,
            viewing = PlaybackViewingTracker(expectedRuntimeMs(state)))
        while (sessions.size > 4) sessions.remove(sessions.keys.first())
        return token
    }
    fun startPositionMs(token: String): Long = sessions[token]?.takeIf { it.scope.account == ids.currentUserId() && it.scope.profile == ids.currentProfileId() }?.start ?: 0
    fun identity(token: String): ApplePlaybackIdentity? = sessions[token]?.takeIf { it.scope.account == ids.currentUserId() && it.scope.profile == ids.currentProfileId() }?.identity
    fun synopsis(token: String): ApplePlaybackSynopsis? {
        val identity = identity(token) ?: return null
        val state = sourceSnapshot(token) ?: return null
        return ApplePlaybackSynopsisPolicy.from(state, identity.season, identity.episode)
    }
    fun preferenceKey(token: String): String? = identity(token)?.let(ApplePlaybackPreferencePolicy::key)
    fun sourceSnapshot(token: String): DetailUiState? = sessions[token]?.takeIf {
        it.scope.account == ids.currentUserId() && it.scope.profile == ids.currentProfileId()
    }?.sourceState

    internal fun recordSource(token: String, source: SourceProfile?) {
        val identity = identity(token) ?: return
        val session = sessions[token] ?: return
        val state = session.sourceState ?: return
        session.source = source?.takeIf { profile ->
            profile.seasonNumber == identity.season && profile.episodeNumber == identity.episode &&
                (state.streams + listOfNotNull(state.autoPlayStream)).any { continuationSourceKey(it) == profile.sourceKey }
        }
    }

    /** Capture before stopping playback, which removes the old progress session asynchronously. */
    fun prepareContinuation(token: String, next: ApplePlaybackIdentity): ApplePlaybackIdentity {
        val target = next.copy(continuationToken = null)
        val current = identity(token) ?: return target
        val session = sessions[token] ?: return target
        if (current.mediaType != MediaType.SERIES || target.mediaType != current.mediaType || target.mediaId != current.mediaId ||
            current.season == null || current.episode == null || target.season == null || target.episode == null ||
            target.season < current.season || (target.season == current.season && target.episode <= current.episode)) return target
        val state = session.sourceState ?: return target
        if (state.streams.isEmpty() && state.autoPlayStream == null) return target
        val ticket = Random.nextLong().toString(16) + Clock.System.now().toEpochMilliseconds().toString(16)
        continuations[ticket] = Handoff(session.scope, AppleEpisodeContinuation(target, session.source), Clock.System.now().toEpochMilliseconds())
        while (continuations.size > 4) continuations.remove(continuations.keys.first())
        return target.copy(continuationToken = ticket)
    }

    internal fun consumeContinuation(token: String, mediaId: String, season: Int, episode: Int): AppleEpisodeContinuation? {
        val handoff = continuations.remove(token) ?: return null
        val target = handoff.request.identity
        return handoff.request.takeIf {
            handoff.scope.account == ids.currentUserId() && handoff.scope.profile == ids.currentProfileId() &&
                Clock.System.now().toEpochMilliseconds() - handoff.createdAt in 0..600_000 &&
                target.mediaId == mediaId && target.season == season && target.episode == episode
        }
    }
    private fun expectedRuntimeMs(state: DetailUiState): Long {
        val item = state.mediaItem ?: return 0
        val target = state.primaryPlaybackTarget
        val runtime = if (item.type == MediaType.MOVIE) item.runtime else {
            val season = state.seasonDetail?.takeIf { it.seasonNumber == target.season }
                ?: item.seasons.firstOrNull { it.seasonNumber == target.season }
            season?.episodes?.firstOrNull { it.episodeNumber == target.episode }?.runtime
        }
        return (runtime?.takeIf { it > 0 }?.toLong() ?: 0) * 60_000
    }
    fun viewingTracker(token: String): PlaybackViewingTracker? = sessions[token]?.takeIf {
        it.scope.account == ids.currentUserId() && it.scope.profile == ids.currentProfileId()
    }?.viewing

    @Throws(Exception::class)
    suspend fun markMeaningfulWatched(token: String, positionMs: Long, durationMs: Long): Boolean {
        if (viewingTracker(token)?.snapshot()?.complete != true) return false
        save(token, positionMs, durationMs, false)
        return true
    }
    @Throws(Exception::class)
    suspend fun save(token: String, positionMs: Long, durationMs: Long, finish: Boolean) = writeLock.withLock {
        val session = sessions[token] ?: return@withLock
        try {
            if (session.scope.account != ids.currentUserId() || session.scope.profile != ids.currentProfileId()) return@withLock
            // Opening, preparing, a paused resume, or a failed decoder is not playback.
            if (positionMs > 0 && durationMs > 0 && session.viewing.hasPlayed) withContext(session.scope) {
                if (session.replay) {
                    val prefs = replayPrefs ?: return@withContext
                    val saved = runCatching { replayJson.decodeFromString<Map<String, ReplayProgress>>(prefs.getString(replayProgressKey()).orEmpty()) }.getOrDefault(emptyMap())
                    val values = ReplayProgressPolicy.record(saved, session.template.mediaId, positionMs, durationMs,
                        session.viewing.hasPlayed, Clock.System.now().toEpochMilliseconds())
                    prefs.setString(replayProgressKey(), replayJson.encodeToString(values))
                    return@withContext
                }
                if (!session.restored) {
                    progress.playbackCoverage(session.template)?.let(session.viewing::restore)
                    session.restored = true
                }
                progress.savePlaybackProgress(session.template.copy(positionMs = positionMs.coerceAtMost(durationMs), durationMs = durationMs),
                    session.viewing.snapshot())
            }
        } finally { if (finish) sessions.remove(token) }
    }
}
