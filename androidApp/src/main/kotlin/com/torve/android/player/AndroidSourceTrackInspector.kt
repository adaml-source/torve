package com.torve.android.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import com.torve.data.addon.ParsedStream
import com.torve.data.addon.SourceTrackInspector
import com.torve.data.auth.UserIdProvider
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.player.TrackDescription
import com.torve.presentation.lanlibrary.PendingLanPlaybackHandoff
import java.io.File
import java.net.URI
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@androidx.annotation.OptIn(UnstableApi::class)
class AndroidSourceTrackInspector(context: Context, private val users: UserIdProvider) : SourceTrackInspector {
    private val cache = SourceTrackCache(
        File(context.filesDir, "source-tracks-v1.json"),
        fallbackFile = File(context.cacheDir, "source-tracks-v1.json"),
    )
    private val scanningPrefs = context.getSharedPreferences("source_audio_scanning_v1", Context.MODE_PRIVATE)
    fun scanningEnabled() = scanningPrefs.getBoolean(owner(), true)
    fun setScanningEnabled(enabled: Boolean) { scanningPrefs.edit().putBoolean(owner(), enabled).apply() }
    private val probeMutex = Mutex()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun retryFailed() { failedAt.clear() }
    data class LanguageChoice(val language: String, val allowSubtitles: Boolean, val at: Long = System.currentTimeMillis())
    private val choices = mutableMapOf<String, LanguageChoice>()
    val revision = cache.revision
    fun owner() = users.currentDataUserId()
    fun key(stream: ParsedStream) = SourceTrackCache.key(stream, owner())
    override fun cached(stream: ParsedStream) = cache.applying(stream, owner())
    override fun canInspect(stream: ParsedStream) = scanningEnabled() && readableUrl(stream) != null
    fun selectLanguage(stream: ParsedStream, language: String, allowSubtitles: Boolean) {
        if (choices.size > 100) choices.clear()
        key(stream)?.let { choices[it] = LanguageChoice(language, allowSubtitles) }
    }
    fun languageFor(url: String): LanguageChoice? = choices[SourceTrackCache.urlKey(url, owner())]
        ?.takeIf { System.currentTimeMillis() - it.at in 0..1_800_000L }
    override fun associate(stream: ParsedStream, playbackUrl: String) {
        cache.associate(stream, playbackUrl, owner())
        choices.remove(key(stream))?.let { choices[SourceTrackCache.urlKey(playbackUrl, owner())] = it }
    }

    companion object {
        const val BATCH_SIZE = 12
        const val BYTE_LIMIT = 8 * 1024 * 1024L
        fun readableUrl(stream: ParsedStream): String? {
            if (stream.accelerationProvenanceKind == CandidateProvenanceKind.USENET_NZBDAV) return null
            val url = stream.directUrl ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val path = uri.path.orEmpty().lowercase()
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                path.endsWith(".nzb") || path.endsWith(".torrent") || path.contains("/nzb/") || path.contains("/getnzb/") ||
                uri.rawQuery.orEmpty().split('&').any { it.substringBefore('=').equals("t", true) && it.substringAfter('=').equals("get", true) }) return null
            return url
        }
    }

    override suspend fun inspect(stream: ParsedStream): ParsedStream {
        val owner = owner()
        return probeMutex.withLock {
            if (owner != owner() || !scanningEnabled()) return@withLock stream
            val cached = cache.applying(stream, owner)
            if (cached.trackLanguagesVerified) return@withLock cached
            val url = readableUrl(stream) ?: return@withLock stream
            val key = SourceTrackCache.key(stream, owner) ?: return@withLock stream
            if (failedAt[key]?.let { System.currentTimeMillis() - it < 30_000 } == true) return@withLock stream
            val tracks = readTracks(url) ?: run {
                if (failedAt.size > 256) failedAt.clear()
                failedAt[key] = System.currentTimeMillis()
                return@withLock stream
            }
            if (owner != owner()) return@withLock stream
            withContext(Dispatchers.IO) {
                cache.save(listOf(key, SourceTrackCache.urlKey(url, owner)), tracks.first, tracks.second)
            }
            cache.applying(stream, owner)
        }
    }

    suspend fun recordPlayback(url: String, owner: String, audio: List<TrackDescription>, subtitles: List<TrackDescription>) {
        // Empty/partial preparation notifications must not overwrite an inspected file.
        if (audio.isEmpty() || owner != owner()) return
        withContext(Dispatchers.IO) {
            if (owner == owner()) cache.recordPlayback(url, owner, audio.mapNotNull { it.language }, subtitles.mapNotNull { it.language })
        }
    }

    private suspend fun readTracks(url: String): Pair<List<String>, List<String>>? = withTimeoutOrNull(10_000) {
        val budget = InspectionReadBudget(BYTE_LIMIT)
        val requestHeaders = PendingLanPlaybackHandoff.peekFor(url).orEmpty()
        val strictOrigin = requestHeaders["Torve-Internal-Origin-Scope"] == url
        val headers = requestHeaders.filterKeys { it != "Torve-Internal-Origin-Scope" }
        val factory = DataSource.Factory {
            InspectionHttpDataSource(URI(url), headers, strictOrigin, budget)
        }
        try {
            val future = MetadataRetriever.retrieveMetadata(DefaultMediaSourceFactory(factory), MediaItem.fromUri(url))
            val groups = suspendCancellableCoroutine<TrackGroupArray?> { continuation ->
                future.addListener({
                    if (continuation.isActive) continuation.resume(runCatching { future.get() }.getOrNull())
                }, Executor { it.run() })
                continuation.invokeOnCancellation { budget.cancel(); future.cancel(true) }
            } ?: return@withTimeoutOrNull null
            val audio = mutableListOf<String>()
            val subtitles = mutableListOf<String>()
            var hasAudio = false
            for (i in 0 until groups.length) {
                val group = groups[i]
                if (group.type == C.TRACK_TYPE_AUDIO) hasAudio = true
                for (j in 0 until group.length) {
                    val language = group.getFormat(j).language ?: continue
                    when (group.type) {
                        C.TRACK_TYPE_AUDIO -> audio += language
                        C.TRACK_TYPE_TEXT -> subtitles += language
                    }
                }
            }
            if (hasAudio) audio to subtitles else null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { budget.cancel() }
    }
}
