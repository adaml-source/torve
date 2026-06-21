package com.torve.android.player

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import android.net.Uri

fun isCastFrameworkAvailable(context: Context): Boolean {
    return try {
        val playServicesStatus = GoogleApiAvailability
            .getInstance()
            .isGooglePlayServicesAvailable(context)
        if (playServicesStatus != ConnectionResult.SUCCESS) {
            return false
        }
        CastContext.getSharedInstance(context)
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * Manages Google Cast (Chromecast) session and media playback.
 */
class CastManager(context: Context) {

    private var castContext: CastContext? = null
    private var sessionManager: SessionManager? = null
    private var currentSession: CastSession? = null
    private var pendingMedia: PendingMedia? = null

    private val listeners = mutableListOf<CastListener>()

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            currentSession = session
            pendingMedia?.let { pending ->
                castMedia(
                    url = pending.url,
                    title = pending.title,
                    posterUrl = pending.posterUrl,
                    contentType = pending.contentType,
                )
                pendingMedia = null
            }
            listeners.forEach { it.onCastSessionStarted() }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            currentSession = null
            listeners.forEach { it.onCastSessionEnded() }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            currentSession = session
            pendingMedia?.let { pending ->
                castMedia(
                    url = pending.url,
                    title = pending.title,
                    posterUrl = pending.posterUrl,
                    contentType = pending.contentType,
                )
                pendingMedia = null
            }
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    init {
        try {
            castContext = CastContext.getSharedInstance(context)
            sessionManager = castContext?.sessionManager
            sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
        } catch (_: Throwable) {
            // Cast not available (missing Google Play Services, etc.)
        }
    }

    val isCasting: Boolean get() = currentSession?.isConnected == true

    fun requestCast(
        url: String,
        title: String = "",
        posterUrl: String? = null,
    ) {
        val contentType = inferContentType(url)
        if (isCasting) {
            castMedia(url, title, posterUrl, contentType)
        } else {
            pendingMedia = PendingMedia(url, title, posterUrl, contentType)
        }
    }

    fun castMedia(
        url: String,
        title: String = "",
        posterUrl: String? = null,
        contentType: String = "video/mp4",
    ) {
        val session = currentSession ?: return
        val remoteMediaClient = session.remoteMediaClient ?: return

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            posterUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        remoteMediaClient.load(loadRequest)
    }

    fun stopCasting() {
        currentSession?.remoteMediaClient?.stop()
    }

    fun disconnect() {
        sessionManager?.endCurrentSession(true)
    }

    fun addListener(listener: CastListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CastListener) {
        listeners.remove(listener)
    }

    fun release() {
        sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
    }

    interface CastListener {
        fun onCastSessionStarted() {}
        fun onCastSessionEnded() {}
    }

    private data class PendingMedia(
        val url: String,
        val title: String,
        val posterUrl: String?,
        val contentType: String,
    )

    private fun inferContentType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "application/x-mpegURL"
            lower.contains(".mpd") -> "application/dash+xml"
            lower.contains(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }
}
