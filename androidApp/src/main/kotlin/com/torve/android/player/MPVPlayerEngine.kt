package com.torve.android.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import com.torve.domain.player.PlayerEngine
import com.torve.domain.player.PlayerListener
import com.torve.domain.player.PlayerState
import com.torve.domain.player.TrackDescription
import java.util.Locale

/**
 * PlayerEngine backed by libmpv via JNI.
 * Falls back gracefully if native .so files aren't present.
 */
class MPVPlayerEngine(
    private val context: Context,
) : PlayerEngine, MPVLib.EventObserver {

    private var _state = PlayerState()
    override val state: PlayerState get() = _state

    private val listeners = mutableListOf<PlayerListener>()
    private var initialized = false
    /**
     * Headers staged via [setNextRequestHeaders] for the next [play]
     * call only. Mirrors the ExoPlayer pattern so LAN-library URLs that
     * require `X-Torve-Lan-Auth` (and any other authenticated HTTP
     * source) work the same on either engine. One-shot so headers don't
     * leak into the next, unrelated stream.
     */
    private var pendingRequestHeaders: Map<String, String> = emptyMap()
    private var audioPassthroughEnabled = false
    private var preferSurroundCodecs = true
    private var liveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var userAudioPassthroughEnabled = false
    private var userPreferSurroundCodecs = true
    private var userLiveAudioOutputMode = LiveAudioOutputMode.PREFER_COMPATIBLE
    private var currentPlaybackContext: LiveAudioPlaybackContext? = null
    private var rememberedCompatibilityHint: LiveAudioCompatibilityHint? = null
    private var rememberedTrackHintApplied = false
    private var pendingSuccessfulRecoveryKind: LiveAudioRecoveryKind? = null
    private var pendingRecoveryTrackId: Int? = null
    private var currentAudioSignature: String? = null
    private var autoCompatibleTrackSelectionAttempted = false
    private var honorRememberedCompatibilityHint = true
    private var aggressiveAutoTrackSelectionEnabled = true
    private var currentChannel: Channel? = null
    private var activePlaybackProfile: LiveTvPlaybackProfile? = null
    private var activePlaybackPhase = LiveTvPlaybackPhase.ZAP
    private var channelProfileRemembered = false
    private var livePlaybackToken = 0
    private var interlaceDetected = false
    private var estimatedFrameRate: Float? = null
    private var displayFrameRate: Float? = null
    private var mistimedFrameCount: Int? = null
    private var droppedFrameCount: Int? = null
    private var delayedFrameCount: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val promoteSteadyStateRunnable = Runnable {
        applyActivePlaybackProfile(
            phase = LiveTvPlaybackPhase.STEADY,
            reason = "steady_state_promote",
            expectedToken = livePlaybackToken,
        )
    }

    fun initialize(): Boolean {
        if (!MPVLib.tryLoad()) return false
        MPVLib.create(context)
        val liveVideoSyncMode = MPVRuntimeOverrides.liveVideoSyncModeOverride ?: LIVE_VIDEO_SYNC_MODE

        // Match a common IPTV tuning baseline before applying Torve-specific live options.
        MPVLib.setOptionString("profile", "fast")

        // Keep hardware decode preferred, but let the embedded mpv-android player choose
        // the Android video output path that matches attachSurface().
        MPVLib.setOptionString("gpu-api", "opengl")
        MPVLib.setOptionString("hwdec", "auto-safe")
        MPVLib.setOptionString("ao", "audiotrack")
        MPVLib.setOptionString("input-default-bindings", "yes")
        MPVLib.setOptionString("video-sync", liveVideoSyncMode)
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("cache-on-disk", "no")
        MPVLib.setOptionString("cache-pause", "no")
        MPVLib.setOptionString("demuxer-readahead-secs", "8")
        MPVLib.setOptionString("demuxer-lavf-probesize", "2097152")
        MPVLib.setOptionString("demuxer-lavf-analyzeduration", "2500000")
        MPVLib.setOptionString("demuxer-lavf-linearize-timestamps", "yes")
        MPVLib.setOptionString("stream-buffer-size", "1024KiB")
        MPVLib.setOptionString("deinterlace", "no")
        MPVLib.setOptionString("interpolation", "no")
        MPVLib.setOptionString("tscale", "oversample")
        MPVLib.setOptionString("vd-lavc-dr", "yes")

        // Keep enough live buffer for jitter absorption without huge cache swings.
        MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
        MPVLib.setOptionString("demuxer-max-back-bytes", "24MiB")
        MPVLib.setOptionString("cache-secs", "18")

        MPVLib.init()
        MPVLib.addObserver(this)

        // Observe key properties
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("track-list/count", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("aid", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("video-params/interlace-detected", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("estimated-vf-fps", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("display-fps", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("mistimed-frame-count", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("vo-drop-frame-count", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("vo-delayed-frame-count", MPVLib.MPV_FORMAT_INT64)

        initialized = true
        applyAudioOutputPreferences()
        Log.i(TAG, "Initialized mpv live config videoSync=$liveVideoSyncMode gpuApi=opengl hwdec=auto-safe ao=audiotrack")
        return true
    }

    override fun setNextRequestHeaders(headers: Map<String, String>) {
        // Defensive copy - caller may reuse / mutate their map after
        // staging without affecting the captured value.
        pendingRequestHeaders = if (headers.isEmpty()) emptyMap() else headers.toMap()
    }

    // MPV applies the staged headers via the `http-header-fields`
    // libmpv property right before loadfile (see play() below).
    override val supportsRequestHeaders: Boolean = true

    override fun play(url: String) {
        if (!initialized) return
        rememberedTrackHintApplied = false
        autoCompatibleTrackSelectionAttempted = false
        pendingSuccessfulRecoveryKind = null
        pendingRecoveryTrackId = null
        currentAudioSignature = null
        channelProfileRemembered = false
        interlaceDetected = false
        estimatedFrameRate = null
        displayFrameRate = null
        mistimedFrameCount = null
        droppedFrameCount = null
        delayedFrameCount = null
        livePlaybackToken += 1
        mainHandler.removeCallbacks(promoteSteadyStateRunnable)
        applyRememberedCompatibilityHintIfAvailable()
        resolvePlaybackProfileIfNeeded()
        applyActivePlaybackProfile(
            phase = LiveTvPlaybackPhase.ZAP,
            reason = "play_start",
            expectedToken = livePlaybackToken,
        )
        _state = _state.copy(isPlaying = true, isIdle = false, isBuffering = true)
        notifyStateChanged()
        Log.i(
            TAG,
            "Starting live audio session engine=${LivePlayerEngineId.MPV.storageValue} " +
                "channel=${currentPlaybackContext?.displayName ?: "unknown"} " +
                "passthrough=$audioPassthroughEnabled " +
                "audioMode=${liveAudioOutputMode.storageValue} " +
                "preferSurround=$preferSurroundCodecs " +
                "sync=${activePlaybackProfile?.videoSyncMode ?: (MPVRuntimeOverrides.liveVideoSyncModeOverride ?: LIVE_VIDEO_SYNC_MODE)}",
        )
        // Apply staged HTTP headers (e.g. LAN auth) before loadfile so
        // libmpv attaches them to the request. Clear immediately so the
        // next, unrelated stream doesn't inherit them.
        val headers = pendingRequestHeaders
        pendingRequestHeaders = emptyMap()
        if (headers.isNotEmpty()) {
            val joined = formatHttpHeaderFields(headers)
            setStringPropertySafely("http-header-fields", joined)
        } else {
            // Reset so a previously-set header doesn't bleed into a
            // play() that intentionally has none.
            setStringPropertySafely("http-header-fields", "")
        }
        MPVLib.loadFile(url)
        mainHandler.postDelayed(promoteSteadyStateRunnable, STEADY_STATE_PROMOTION_DELAY_MS)
    }

    override fun pause() {
        if (!initialized) return
        MPVLib.pause()
        _state = _state.copy(isPlaying = false, isIdle = false)
        notifyStateChanged()
    }

    override fun resume() {
        if (!initialized) return
        MPVLib.play()
        _state = _state.copy(isPlaying = true, isIdle = false)
        notifyStateChanged()
    }

    override fun stop() {
        if (!initialized) return
        mainHandler.removeCallbacks(promoteSteadyStateRunnable)
        MPVLib.stop()
        _state = PlayerState()
        notifyStateChanged()
    }

    override fun seekTo(positionMs: Long) {
        if (!initialized) return
        MPVLib.seek(positionMs / 1000.0)
    }

    override fun seekRelative(deltaMs: Long) {
        if (!initialized) return
        MPVLib.seekRelative(deltaMs / 1000.0)
    }

    override fun setSpeed(speed: Float) {
        if (!initialized) return
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    override fun getSubtitleTracks(): List<TrackDescription> {
        if (!initialized) return emptyList()
        return MPVLib.getTracks()
            .filter { it.type == "sub" }
            .map { track ->
                TrackDescription(
                    id = track.id,
                    label = track.title ?: track.language ?: "Subtitle ${track.id}",
                    language = track.language,
                    isSelected = track.isSelected,
                    formatHint = track.codec,
                )
            }
    }

    override fun getAudioTracks(): List<TrackDescription> {
        if (!initialized) return emptyList()
        return MPVLib.getTracks()
            .filter { it.type == "audio" }
            .map { track ->
                TrackDescription(
                    id = track.id,
                    label = track.title ?: track.language ?: "Audio ${track.id}",
                    language = track.language,
                    isSelected = track.isSelected,
                    formatHint = track.codec,
                )
            }
    }

    override fun selectSubtitleTrack(id: Int) {
        if (!initialized) return
        MPVLib.selectSubtitleTrack(id)
    }

    override fun selectAudioTrack(id: Int) {
        if (!initialized) return
        MPVLib.selectAudioTrack(id)
    }

    override fun disableSubtitles() {
        if (!initialized) return
        MPVLib.disableSubtitles()
    }

    override fun setSubtitleDelay(delayMs: Int) {
        if (!initialized) return
        MPVLib.setPropertyDouble("sub-delay", delayMs / 1000.0)
    }

    override fun getSubtitleDelay(): Int {
        if (!initialized) return 0
        return try { (MPVLib.getPropertyDouble("sub-delay") * 1000).toInt() } catch (_: Exception) { 0 }
    }

    override fun setAudioDelay(delayMs: Int) {
        if (!initialized) return
        val seconds = delayMs / 1000.0
        if (delayMs == 0) return
        MPVLib.setPropertyString("audio-delay", seconds.toString())
    }

    override fun getAudioDelay(): Int {
        if (!initialized) return 0
        return try {
            (MPVLib.getPropertyDouble("audio-delay") * 1000).toInt()
        } catch (_: Exception) {
            0
        }
    }

    internal fun getPlaybackRuntimeInfo(): PlaybackRuntimeInfo {
        val videoCodec = runCatching { MPVLib.getPropertyString("video-codec") }.getOrNull()
        val audioCodec = runCatching { MPVLib.getPropertyString("audio-codec-name") }.getOrNull()
            ?: runCatching { MPVLib.getPropertyString("audio-codec") }.getOrNull()
        val videoWidth = runCatching { MPVLib.getPropertyInt("video-params/w") }.getOrNull()?.takeIf { it > 0 }
        val videoHeight = runCatching { MPVLib.getPropertyInt("video-params/h") }.getOrNull()?.takeIf { it > 0 }
        val decoderName = runCatching { MPVLib.getPropertyString("hwdec-current") }.getOrNull()
        val frameRate = runCatching { MPVLib.getPropertyDouble("estimated-vf-fps").toFloat() }.getOrNull()
            ?.takeIf { it > 0f }
        val selectedAudioTrack = getAudioTracks().firstOrNull { it.isSelected }
        val selectedSubtitleTrack = getSubtitleTracks().firstOrNull { it.isSelected }
        val videoDecoderKind = when {
            decoderName.isNullOrBlank() || decoderName == "no" -> DecoderKind.UNKNOWN
            else -> DecoderKind.HARDWARE
        }
        return PlaybackRuntimeInfo(
            engineId = LivePlayerEngineId.MPV,
            videoCodec = videoCodec?.normalizeFormatKey(),
            audioCodec = audioCodec?.normalizeFormatKey(),
            videoDecoderName = decoderName,
            audioDecoderName = "system",
            videoDecoderKind = videoDecoderKind,
            audioDecoderKind = DecoderKind.UNKNOWN,
            resolutionLabel = if (videoWidth != null && videoHeight != null) "${videoWidth}x${videoHeight}" else null,
            frameRate = frameRate,
            selectedAudioTrack = selectedAudioTrack,
            selectedSubtitleTrack = selectedSubtitleTrack,
            outputMode = liveAudioOutputMode,
            passthroughEnabled = audioPassthroughEnabled,
            preferSurround = preferSurroundCodecs,
            fallbackFromHardwareDefault = videoDecoderKind != DecoderKind.HARDWARE,
        )
    }

    fun setPictureFormat(aspectRatio: Float?, fill: Boolean) {
        if (!initialized) return
        if (fill) {
            MPVLib.setPropertyString("video-aspect-override", "-1")
            MPVLib.setPropertyString("panscan", "1.0")
        } else if (aspectRatio != null) {
            MPVLib.setPropertyString("video-aspect-override", aspectRatio.toString())
            MPVLib.setPropertyString("panscan", "0.0")
        } else {
            // Leave the default mpv output geometry alone on first startup.
            MPVLib.setPropertyString("video-aspect-override", "-1")
            MPVLib.setPropertyString("panscan", "0.0")
        }
    }

    fun setPlaybackVolume(volume: Float) {
        if (!initialized) return
        val percent = (volume.coerceIn(0f, 1f) * 100.0).coerceIn(0.0, 100.0)
        runCatching { MPVLib.setPropertyDouble("volume", percent) }
            .onFailure { Log.w(TAG, "Unable to set mpv playback volume=$percent", it) }
    }

    fun setAudioOutputPreferences(
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode = userLiveAudioOutputMode,
    ) {
        userAudioPassthroughEnabled = passthroughEnabled
        userPreferSurroundCodecs = preferSurround
        userLiveAudioOutputMode = outputMode
        rememberedCompatibilityHint = null
        rememberedTrackHintApplied = false
        autoCompatibleTrackSelectionAttempted = false
        pendingSuccessfulRecoveryKind = null
        pendingRecoveryTrackId = null
        audioPassthroughEnabled = passthroughEnabled
        preferSurroundCodecs = preferSurround
        liveAudioOutputMode = outputMode
        applyAudioOutputPreferences()
    }

    fun setLivePlaybackContext(channel: Channel?, honorRememberedHint: Boolean = true) {
        honorRememberedCompatibilityHint = honorRememberedHint
        currentChannel = channel
        currentPlaybackContext = channel?.let(LiveAudioPlaybackContext::fromChannel)
        rememberedCompatibilityHint = if (honorRememberedHint) {
            currentPlaybackContext?.let {
                LiveAudioCompatibilityStore.resolveHint(context, it)
            }
        } else {
            null
        }
        rememberedTrackHintApplied = false
        pendingRecoveryTrackId = null
        currentAudioSignature = null
        channelProfileRemembered = false
        resolvePlaybackProfileIfNeeded()
    }

    fun setAggressiveAutoTrackSelectionEnabled(enabled: Boolean) {
        aggressiveAutoTrackSelectionEnabled = enabled
    }

    private fun applyAudioOutputPreferences() {
        if (!initialized) return
        val forceStereo = liveAudioOutputMode == LiveAudioOutputMode.FORCE_STEREO_PCM
        val preferCompatible = liveAudioOutputMode == LiveAudioOutputMode.PREFER_COMPATIBLE
        val passthrough = audioPassthroughEnabled && !forceStereo
        MPVLib.setPropertyString(
            "audio-spdif",
            if (passthrough) "ac3,eac3,dts,dts-hd,truehd" else "",
        )

        val channels = when {
            forceStereo -> "stereo"
            preferCompatible -> "stereo"
            preferSurroundCodecs -> "auto-safe"
            else -> "stereo"
        }
        MPVLib.setPropertyString("audio-channels", channels)
    }

    private fun applyRememberedCompatibilityHintIfAvailable() {
        rememberedCompatibilityHint = if (honorRememberedCompatibilityHint) {
            currentPlaybackContext?.let {
                LiveAudioCompatibilityStore.resolveHint(context, it)
            }
        } else {
            null
        }
        rememberedTrackHintApplied = false
        val hint = rememberedCompatibilityHint
        if (hint != null) {
            Log.d(
                TAG,
                "Applying remembered live audio hint kind=${hint.recoveryKind} mode=${hint.outputMode} channel=${currentPlaybackContext?.displayName.orEmpty()}",
            )
            audioPassthroughEnabled = hint.passthroughEnabled
            preferSurroundCodecs = hint.preferSurround
            liveAudioOutputMode = hint.liveAudioOutputMode()
        } else {
            audioPassthroughEnabled = userAudioPassthroughEnabled
            preferSurroundCodecs = userPreferSurroundCodecs
            liveAudioOutputMode = userLiveAudioOutputMode
        }
        applyAudioOutputPreferences()
    }

    internal fun recordLiveTvIssue(reason: String) {
        val channel = currentChannel ?: return
        val playbackContext = currentPlaybackContext ?: return
        val profile = activePlaybackProfile ?: return
        activePlaybackProfile = LiveTvPlaybackProfileStore.recordPlaybackIssue(
            context = context,
            channel = channel,
            playbackContext = playbackContext,
            profile = profile,
            reason = reason,
            observation = buildRuntimeObservation(),
        )
        channelProfileRemembered = false
    }

    private fun resolvePlaybackProfileIfNeeded() {
        val channel = currentChannel ?: return
        val playbackContext = currentPlaybackContext ?: return
        activePlaybackProfile = LiveTvPlaybackProfileStore.resolve(
            context = context,
            channel = channel,
            playbackContext = playbackContext,
        )
    }

    private fun applyActivePlaybackProfile(
        phase: LiveTvPlaybackPhase,
        reason: String,
        expectedToken: Int,
    ) {
        if (!initialized || expectedToken != livePlaybackToken) return
        val profile = activePlaybackProfile ?: return
        activePlaybackPhase = phase

        setStringPropertySafely(
            "video-sync",
            MPVRuntimeOverrides.liveVideoSyncModeOverride ?: profile.videoSyncMode().storageValue,
        )
        setStringPropertySafely("hwdec", profile.hardwareDecodeMode)
        setBooleanPropertySafely(
            "deinterlace",
            when (profile.deinterlaceMode()) {
                LiveTvDeinterlaceMode.FORCE -> true
                LiveTvDeinterlaceMode.OFF -> false
                LiveTvDeinterlaceMode.AUTO -> profile.interlacedSeen || interlaceDetected
            },
        )
        setBooleanPropertySafely("interpolation", profile.interpolationEnabled)
        setStringPropertySafely("tscale", profile.tscaleMode)
        setStringPropertySafely("cache", "yes")
        setStringPropertySafely("cache-on-disk", if (profile.diskCacheEnabled) "yes" else "no")
        setStringPropertySafely("cache-pause", "no")
        setStringPropertySafely("demuxer-readahead-secs", profile.readaheadSecs(phase).toString())
        setStringPropertySafely("cache-secs", profile.cacheSecs(phase).toString())
        setStringPropertySafely("demuxer-max-bytes", "${profile.maxBytesMiB(phase)}MiB")
        setStringPropertySafely("demuxer-max-back-bytes", "${profile.backBufferMiB}MiB")
        setStringPropertySafely("stream-buffer-size", "${profile.streamBufferKiB}KiB")
        setStringPropertySafely("demuxer-lavf-probesize", profile.demuxerProbeBytes.toString())
        setStringPropertySafely("demuxer-lavf-analyzeduration", profile.demuxerAnalyzeDurationUs.toString())
        setStringPropertySafely(
            "demuxer-lavf-linearize-timestamps",
            if (profile.linearizeTimestamps) "yes" else "no",
        )
        setStringPropertySafely("force-seekable", if (profile.forceSeekable) "yes" else "no")
        setStringPropertySafely("vd-lavc-dr", if (phase == LiveTvPlaybackPhase.STEADY) "yes" else "no")
        Log.i(
            TAG,
            "Applied live TV profile channel=${currentPlaybackContext?.displayName.orEmpty()} " +
                "phase=${phase.name.lowercase(Locale.ROOT)} reason=$reason " +
                "sync=${profile.videoSyncMode} deinterlace=${profile.deinterlaceMode} " +
                "cache=${profile.cacheSecs(phase)}s readAhead=${profile.readaheadSecs(phase)}s " +
                "maxBytes=${profile.maxBytesMiB(phase)}MiB back=${profile.backBufferMiB}MiB",
        )
    }

    private fun applyDynamicVideoAdjustments(reason: String) {
        val profile = activePlaybackProfile ?: return
        if (profile.deinterlaceMode() != LiveTvDeinterlaceMode.AUTO) return
        setBooleanPropertySafely("deinterlace", interlaceDetected)
        Log.d(
            TAG,
            "Updated dynamic live TV video flags channel=${currentPlaybackContext?.displayName.orEmpty()} " +
                "reason=$reason interlaced=$interlaceDetected phase=${activePlaybackPhase.name.lowercase(Locale.ROOT)}",
        )
    }

    private fun rememberSuccessfulLiveProfileIfNeeded(trigger: String) {
        if (channelProfileRemembered) return
        val channel = currentChannel ?: return
        val playbackContext = currentPlaybackContext ?: return
        val profile = activePlaybackProfile ?: return
        activePlaybackProfile = LiveTvPlaybackProfileStore.rememberSuccessfulPlayback(
            context = context,
            channel = channel,
            playbackContext = playbackContext,
            profile = profile,
            observation = buildRuntimeObservation(),
        )
        channelProfileRemembered = true
        Log.i(
            TAG,
            "Remembered live TV playback profile channel=${playbackContext.displayName} " +
                "trigger=$trigger sync=${activePlaybackProfile?.videoSyncMode ?: "unknown"} " +
                "interlaced=${activePlaybackProfile?.interlacedSeen ?: false}",
        )
    }

    private fun buildRuntimeObservation(): LiveTvRuntimeObservation {
        return LiveTvRuntimeObservation(
            interlacedDetected = interlaceDetected,
            estimatedFrameRate = estimatedFrameRate,
            displayFrameRate = displayFrameRate,
            mistimedFrameCount = mistimedFrameCount,
            droppedFrameCount = droppedFrameCount,
            delayedFrameCount = delayedFrameCount,
        )
    }

    private fun setStringPropertySafely(name: String, value: String) {
        runCatching { MPVLib.setPropertyString(name, value) }
            .onFailure { Log.w(TAG, "Unable to set mpv property $name=$value", it) }
    }

    private fun setBooleanPropertySafely(name: String, value: Boolean) {
        runCatching { MPVLib.setPropertyBoolean(name, value) }
            .onFailure { Log.w(TAG, "Unable to set mpv property $name=$value", it) }
    }

    override fun release() {
        if (!initialized) return
        mainHandler.removeCallbacks(promoteSteadyStateRunnable)
        MPVLib.removeObserver(this)
        MPVLib.destroy()
        initialized = false
    }

    override fun addListener(listener: PlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)
    }

    // --- MPVLib.EventObserver ---

    override fun onPropertyChange(property: String, value: Any?) {
        when (property) {
            "pause" -> {
                val paused = value as? Boolean ?: return
                _state = _state.copy(isPlaying = !paused, isIdle = false)
                if (!paused) {
                    confirmSuccessfulRecoveryIfNeeded()
                }
                notifyStateChanged()
            }
            "time-pos" -> {
                val seconds = (value as? Double) ?: return
                _state = _state.copy(
                    positionMs = (seconds * 1000).toLong(),
                    isPlaying = true,
                    isIdle = false,
                )
                if (seconds > 0.5) {
                    confirmSuccessfulRecoveryIfNeeded()
                }
                if (seconds >= 1.0) {
                    rememberSuccessfulLiveProfileIfNeeded(trigger = "time_pos")
                }
                notifyStateChanged()
            }
            "duration" -> {
                val seconds = (value as? Double) ?: return
                _state = _state.copy(durationMs = (seconds * 1000).toLong())
                notifyStateChanged()
            }
            "paused-for-cache" -> {
                val buffering = value as? Boolean ?: return
                _state = _state.copy(isBuffering = buffering)
                notifyStateChanged()
            }
            "track-list/count" -> {
                val tracks = MPVLib.getTracks()
                currentAudioSignature = tracks.buildAudioSignature()
                logAudioInventory("track_list_updated", tracks)
                invalidateRememberedHintIfTrackMetadataChanged(currentAudioSignature.orEmpty())
                applyRememberedCompatibleTrackIfNeeded()
                applyAutoCompatibleTrackIfNeeded(tracks)
                notifyTracksChanged()
            }
            "aid" -> {
                val tracks = MPVLib.getTracks()
                logAudioInventory("selected_audio_track_changed", tracks)
                notifyTracksChanged()
            }
            "video-params/interlace-detected" -> {
                interlaceDetected = (value as? Boolean) == true
                applyDynamicVideoAdjustments("interlace_detected")
            }
            "estimated-vf-fps" -> {
                estimatedFrameRate = (value as? Double)?.toFloat()?.takeIf { it > 0f }
            }
            "display-fps" -> {
                displayFrameRate = (value as? Double)?.toFloat()?.takeIf { it > 0f }
            }
            "mistimed-frame-count" -> {
                mistimedFrameCount = value.asIntOrNull()
            }
            "vo-drop-frame-count" -> {
                droppedFrameCount = value.asIntOrNull()
            }
            "vo-delayed-frame-count" -> {
                delayedFrameCount = value.asIntOrNull()
            }
        }
    }

    override fun onEvent(eventId: Int) {
        // MPV event IDs: 7 = end-file, etc.
        when (eventId) {
            7 -> { // MPV_EVENT_END_FILE
                if (!channelProfileRemembered && _state.positionMs in 1L until EARLY_END_FILE_THRESHOLD_MS) {
                    recordLiveTvIssue("end_file_before_stable_playback")
                }
                _state = _state.copy(isPlaying = false, isIdle = true)
                notifyStateChanged()
            }
        }
    }

    private fun notifyStateChanged() {
        listeners.forEach { it.onStateChanged(_state) }
    }

    private fun notifyTracksChanged() {
        val audio = getAudioTracks()
        val subtitles = getSubtitleTracks()
        listeners.forEach { it.onTracksChanged(audio, subtitles) }
    }

    private fun confirmSuccessfulRecoveryIfNeeded() {
        val recoveryKind = pendingSuccessfulRecoveryKind ?: return
        val playbackContext = currentPlaybackContext
        val selectedTrack = MPVLib.getTracks()
            .firstOrNull { it.type == "audio" && it.isSelected }
        if (pendingRecoveryTrackId != null && selectedTrack?.id != pendingRecoveryTrackId) {
            return
        }
        val selectedTrackHint = selectedTrack
            ?.toTrackHint()
        if (playbackContext != null) {
            rememberedCompatibilityHint = LiveAudioCompatibilityStore.rememberSuccessfulRecovery(
                context = context,
                playbackContext = playbackContext,
                passthroughEnabled = audioPassthroughEnabled,
                preferSurround = preferSurroundCodecs,
                outputMode = liveAudioOutputMode,
                recoveryKind = recoveryKind,
                engineId = LivePlayerEngineId.MPV,
                preferredTrack = selectedTrackHint,
                audioSignature = currentAudioSignature,
            )
            LiveAudioCompatibilityStore.clearSessionIncompatible(playbackContext)
        }
        pendingSuccessfulRecoveryKind = null
        pendingRecoveryTrackId = null
        rememberedTrackHintApplied = selectedTrackHint != null
        rememberSuccessfulLiveProfileIfNeeded(trigger = "recovery_${recoveryKind.name.lowercase(Locale.ROOT)}")
        listeners.forEach { it.onError(recoveryMessageFor(recoveryKind)) }
    }

    private fun recoveryMessageFor(kind: LiveAudioRecoveryKind): String {
        return when (kind) {
            LiveAudioRecoveryKind.MOBILE_REFERENCE_FIRST_PASS -> "Audio confirmed with the mobile-reference playback path."
            LiveAudioRecoveryKind.COMPATIBLE_MODE,
            LiveAudioRecoveryKind.STEREO_PCM -> "Audio recovered by changing the audio mode."
            LiveAudioRecoveryKind.COMPATIBLE_TRACK -> "Switched to a compatible audio track."
            LiveAudioRecoveryKind.SOFTWARE_AUDIO -> "Audio recovered with software decoding."
            LiveAudioRecoveryKind.ENGINE_FALLBACK -> "Audio recovered by changing the playback engine."
        }
    }

    private fun logAudioInventory(event: String, tracks: List<MPVLib.Track>) {
        if (currentPlaybackContext == null) return
        Log.d(
            TAG,
            "Live audio inventory: engine=${LivePlayerEngineId.MPV.storageValue} " +
                "event=$event channel=${currentPlaybackContext?.displayName.orEmpty()} " +
                "passthrough=$audioPassthroughEnabled mode=${liveAudioOutputMode.storageValue} " +
                "preferSurround=$preferSurroundCodecs inventory=${tracks.audioInventorySummary()}",
        )
    }

    private fun invalidateRememberedHintIfTrackMetadataChanged(audioSignature: String) {
        val playbackContext = currentPlaybackContext ?: return
        val hint = rememberedCompatibilityHint ?: return
        val storedSignature = hint.audioSignature ?: return
        if (storedSignature == audioSignature) return

        Log.i(TAG, "Invalidating remembered live audio hint for ${playbackContext.displayName} because mpv track metadata changed")
        LiveAudioCompatibilityStore.invalidateHint(context, playbackContext)
        rememberedCompatibilityHint = null
        rememberedTrackHintApplied = false
        audioPassthroughEnabled = userAudioPassthroughEnabled
        preferSurroundCodecs = userPreferSurroundCodecs
        liveAudioOutputMode = userLiveAudioOutputMode
        applyAudioOutputPreferences()
    }

    private fun applyRememberedCompatibleTrackIfNeeded() {
        val hint = rememberedCompatibilityHint ?: return
        val preferredTrack = hint.preferredTrack ?: return
        if (rememberedTrackHintApplied) {
            return
        }

        val currentTracks = MPVLib.getTracks().filter { it.type == "audio" }
        val selectedTrack = currentTracks.firstOrNull { it.isSelected }
        if (selectedTrack != null && selectedTrack.matches(preferredTrack)) {
            rememberedTrackHintApplied = true
            return
        }

        val candidate = currentTracks.maxByOrNull { it.matchScore(preferredTrack) }
            ?.takeIf { it.matchScore(preferredTrack) >= 3 }
            ?: return

        rememberedTrackHintApplied = true
        Log.d(
            TAG,
            "Applying remembered compatible mpv audio track ${candidate.codec ?: "unknown"} for ${currentPlaybackContext?.displayName.orEmpty()}",
        )
        MPVLib.selectAudioTrack(candidate.id)
    }

    private fun applyAutoCompatibleTrackIfNeeded(tracks: List<MPVLib.Track>) {
        if (autoCompatibleTrackSelectionAttempted) return
        val audioTracks = tracks.filter { it.type == "audio" }
        if (audioTracks.size < 2) return

        val selectedTrack = audioTracks.firstOrNull { it.isSelected }
        if (!aggressiveAutoTrackSelectionEnabled && selectedTrack != null) return
        val bestCandidate = audioTracks.maxByOrNull { track -> track.compatibilityScore(selectedTrack) } ?: return
        val selectedScore = selectedTrack?.compatibilityScore(selectedTrack) ?: Int.MIN_VALUE
        val bestScore = bestCandidate.compatibilityScore(selectedTrack)
        val shouldSwitch = selectedTrack == null ||
            (bestCandidate.id != selectedTrack.id && bestScore >= selectedScore + MIN_AUTO_TRACK_SWITCH_DELTA)
        if (!shouldSwitch) return

        autoCompatibleTrackSelectionAttempted = true
        pendingSuccessfulRecoveryKind = LiveAudioRecoveryKind.COMPATIBLE_TRACK
        pendingRecoveryTrackId = bestCandidate.id
        Log.i(
            TAG,
            "Auto-selecting mpv audio track ${bestCandidate.codec ?: "unknown"} " +
                "for ${currentPlaybackContext?.displayName.orEmpty()} selected=${selectedTrack?.codec ?: "none"} " +
                "bestScore=$bestScore selectedScore=$selectedScore inventory=${audioTracks.audioInventorySummary()}",
        )
        MPVLib.selectAudioTrack(bestCandidate.id)
    }

    private fun List<MPVLib.Track>.buildAudioSignature(): String {
        return filter { it.type == "audio" }
            .map { track ->
                listOf(
                    track.codec.normalizeFormatKey().orEmpty(),
                    track.language?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                    track.title?.trim()?.lowercase(Locale.ROOT).orEmpty(),
                ).joinToString(separator = ":")
            }
            .sorted()
            .joinToString(separator = "|")
            .take(512)
    }

    private fun List<MPVLib.Track>.audioInventorySummary(): String {
        return filter { it.type == "audio" }
            .joinToString(separator = " | ") { track ->
                val selectedMarker = if (track.isSelected) "*" else ""
                "$selectedMarker${track.debugSummary()}"
            }
            .take(1024)
    }

    private fun MPVLib.Track.debugSummary(): String {
        return listOf(
            "id=$id",
            "codec=${codec ?: "unknown"}",
            "lang=${language ?: "und"}",
            "title=${title ?: "n/a"}",
            "default=$isDefault",
            "selected=$isSelected",
        ).joinToString(separator = ",")
    }

    private fun MPVLib.Track.toTrackHint(): LiveAudioTrackHint {
        return LiveAudioTrackHint(
            label = title,
            language = language,
            formatKey = codec.normalizeFormatKey(),
        )
    }

    private fun MPVLib.Track.matches(trackHint: LiveAudioTrackHint): Boolean {
        return codec.normalizeFormatKey() == trackHint.formatKey &&
            language.equals(trackHint.language, ignoreCase = true) &&
            title.equals(trackHint.label, ignoreCase = true)
    }

    private fun MPVLib.Track.matchScore(trackHint: LiveAudioTrackHint): Int {
        var score = 0
        if (codec.normalizeFormatKey() == trackHint.formatKey) score += 6
        if (language.equals(trackHint.language, ignoreCase = true)) score += 3
        if (title.equals(trackHint.label, ignoreCase = true)) score += 2
        return score
    }

    private fun MPVLib.Track.compatibilityScore(selectedTrack: MPVLib.Track?): Int {
        var score = codecPreferenceScore(codec.normalizeFormatKey())
        if (isDefault) score += 10
        if (title.isNullOrBlank()) score -= 2
        if (language.isNullOrBlank()) score -= 1
        if (selectedTrack?.id == id) score -= 40
        return score
    }

    private fun codecPreferenceScore(normalizedCodec: String?): Int {
        return when (normalizedCodec) {
            "aac",
            "mp4a",
            "audio/aac",
            "audio/mp4a-latm" -> 190
            "opus",
            "audio/opus" -> 175
            "mp3",
            "mp2",
            "mpeg-l2",
            "audio/mp2",
            "audio/mpeg-l2",
            "mpeg",
            "audio/mpeg" -> 168
            "pcm",
            "audio/raw" -> 160
            "flac",
            "audio/flac" -> 150
            "vorbis",
            "audio/vorbis" -> 145
            "ac4",
            "audio/ac4" -> if (audioPassthroughEnabled) 138 else 120
            "eac3",
            "ec-3",
            "audio/eac3",
            "audio/eac3-joc" -> when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> 26
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 72
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 152 else 90
            }
            "ac3",
            "audio/ac3" -> when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> 22
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 68
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 146 else 82
            }
            "dts",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "truehd",
            "mlp",
            "audio/true-hd" -> when (liveAudioOutputMode) {
                LiveAudioOutputMode.FORCE_STEREO_PCM -> -40
                LiveAudioOutputMode.PREFER_COMPATIBLE -> 20
                LiveAudioOutputMode.AUTO -> if (audioPassthroughEnabled || preferSurroundCodecs) 170 else 24
            }
            null -> -12
            else -> 32
        }
    }

    private fun String?.normalizeFormatKey(): String? {
        return this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

    private fun Any?.asIntOrNull(): Int? {
        return when (this) {
            is Int -> this
            is Long -> this.toInt()
            is Double -> this.toInt()
            is Float -> this.toInt()
            is String -> this.toIntOrNull()
            else -> null
        }
    }

    private companion object {
        private const val TAG = "MPVPlayerEngine"
        private const val MIN_AUTO_TRACK_SWITCH_DELTA = 35
        private const val LIVE_VIDEO_SYNC_MODE = "display-resample"
        private const val STEADY_STATE_PROMOTION_DELAY_MS = 2_500L
        private const val EARLY_END_FILE_THRESHOLD_MS = 5_000L
    }
}

internal object MPVRuntimeOverrides {
    @Volatile
    var liveVideoSyncModeOverride: String? = null
}

/**
 * Encode an HTTP header map into the comma-separated `Name: value`
 * form mpv expects for the `http-header-fields` property.
 *
 * Strips CR and LF from header names and values so a malformed header
 * cannot inject extra fields (defense-in-depth - callers already
 * sanitise, but mpv silently truncates on a stray newline). Trims the
 * name; preserves intentional whitespace inside the value.
 *
 * Internal so [MPVPlayerEngineHeaderTest] can exercise it without a
 * real libmpv.
 */
internal fun formatHttpHeaderFields(headers: Map<String, String>): String {
    if (headers.isEmpty()) return ""
    return headers.entries.joinToString(",") { (rawName, rawValue) ->
        val name = rawName.replace("\r", "").replace("\n", "").trim()
        val value = rawValue.replace("\r", "").replace("\n", "")
        "$name: $value"
    }
}
