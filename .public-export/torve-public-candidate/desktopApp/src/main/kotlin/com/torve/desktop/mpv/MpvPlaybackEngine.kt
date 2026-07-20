package com.torve.desktop.mpv

import com.sun.jna.Pointer
import com.torve.desktop.playback.DesktopPlaybackEngine
import com.torve.desktop.playback.DesktopPlaybackEngineEvent
import com.torve.desktop.playback.DesktopPlaybackSession
import com.torve.desktop.playback.PlaybackStartupTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * MPV-backed [DesktopPlaybackEngine] - Stage 1 implementation.
 *
 * This wraps libmpv via JNA and handles every method on the engine
 * interface. **Render path note:** this Stage 1 cut runs mpv with
 * `--force-window=yes` so it opens its own native window for the video
 * surface. That's intentional and explicit - proper in-Compose
 * rendering needs the libmpv render API + a Skia/GL interop layer,
 * which is Stage 3 work.
 *
 * Even with the separate-window caveat, this gives users:
 *  - Hardware decode (`hwdec=auto`)
 *  - Full subtitle / audio track / chapter support that VLC's bundled
 *    handlers struggle with
 *  - Proper HDR tone-mapping and Dolby Vision passthrough on supported
 *    GPUs
 *  - Skip-by-chapter and frame-stepping
 *
 * The engine probes for libmpv on app start; if not found, the user
 * sees a clean "Install mpv" message and the app falls back to VLC.
 */
class MpvPlaybackEngine : DesktopPlaybackEngine {

    /**
     * Tag stored in the shared [com.torve.desktop.vlc.AudioDevicePreferences]
     * file as the `outputName` so MPV-saved devices don't get applied to
     * the VLC engine and vice versa.
     */
    internal val AUDIO_OUTPUT_TAG: String = "mpv"

    override val backendLabel = "MPV (experimental)"
    override val runtimeRequirement =
        "Install mpv (https://mpv.io) so the libmpv shared library is on the system path."

    /**
     * Always true - the MPV engine *intends* to render in-Compose. The
     * V2App shell uses this to decide whether to mount the embedded
     * surface; if we returned `embeddedHandle != null` we'd hit a
     * chicken-and-egg loop (surface only mounts when embedded is true,
     * but embedded only becomes true after the surface mounts and
     * attaches its canvas).
     *
     * Standalone-window fallback is the *failure* path inside
     * [ensureInitialized] when [open] runs without a surface attaching
     * in time, not the engine's declared contract.
     */
    override val isEmbedded: Boolean = true

    private val _events = MutableSharedFlow<DesktopPlaybackEngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<DesktopPlaybackEngineEvent> = _events

    /**
     * Live track list - updated whenever mpv emits a `track-list`
     * property change. UI surfaces collect from this so the audio /
     * subtitle menus refresh the moment a new file finishes loading.
     */
    private val _tracks = MutableStateFlow<List<MpvTrack>>(emptyList())
    val tracks: StateFlow<List<MpvTrack>> = _tracks.asStateFlow()

    /**
     * mpv-reported volume (0..100). Updated from the `volume` property
     * change events so external slider state stays in sync when mpv's
     * own OSC adjusts it.
     */
    private val _volume = MutableStateFlow<Int?>(null)
    val volume: StateFlow<Int?> = _volume.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /**
     * Live audio-device list - refreshed on `audio-device-list` property
     * changes (USB DAC plug/unplug, default-device change). Empty until
     * the first observation lands.
     */
    private val _audioDevices = MutableStateFlow<List<MpvAudioDevice>>(emptyList())
    val audioDevices: StateFlow<List<MpvAudioDevice>> = _audioDevices.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lib: LibMpv? = null
    private var ctx: Pointer? = null
    private var eventLoop: Job? = null
    private var currentMediaPath: String? = null

    /**
     * Native window handle (HWND on Windows, XID on X11, NSView pointer
     * on macOS) the mpv backend should paint into via the legacy `wid`
     * property. `null` means standalone - mpv pops its own window.
     */
    @Volatile
    private var embeddedHandle: Long? = null
    @Volatile
    private var initialized: Boolean = false

    override suspend fun probeRuntime(startupTrace: PlaybackStartupTrace?) = withContext(Dispatchers.IO) {
        startupTrace?.mark("MPV runtime probe start")
        val loaded = LibMpv.loadOrNull()
        if (loaded == null) {
            emit(
                DesktopPlaybackEngineEvent.RuntimeProbe(
                    found = false,
                    message = "libmpv not found. Install mpv from https://mpv.io and ensure libmpv-2 is on the system path.",
                ),
            )
            return@withContext
        }
        lib = loaded
        // Create the handle so options can be set, but defer
        // mpv_initialize until either (a) attachToWindow lands a native
        // handle, or (b) open() forces a standalone-window init. This
        // matters because the `wid` option only works pre-init.
        val handle = loaded.mpv_create()
        if (handle == null) {
            emit(
                DesktopPlaybackEngineEvent.RuntimeProbe(
                    found = false,
                    message = "libmpv loaded but mpv_create() returned null. Library may be corrupt or version-mismatched.",
                ),
            )
            return@withContext
        }
        applyDefaultOptions(loaded, handle)
        ctx = handle
        emit(
            DesktopPlaybackEngineEvent.RuntimeProbe(
                found = true,
                message = "mpv ready. Embedded rendering activates once a Compose surface attaches; otherwise mpv opens its own window.",
            ),
        )
        emit(
            DesktopPlaybackEngineEvent.RuntimeReady(
                executablePath = "libmpv (JNA-loaded)",
                discoverySource = "JNA Native.load",
                processId = ProcessHandle.current().pid(),
            ),
        )
    }

    /**
     * Default options applied **before** mpv_initialize. Anything that
     * needs to vary per-attach (`wid`, `force-window`) is set in
     * [attachToWindow] / [ensureInitialized] instead.
     */
    private fun applyDefaultOptions(mpv: LibMpv, handle: Pointer) {
        mpv.mpv_set_option_string(handle, "keep-open", "yes")
        mpv.mpv_set_option_string(handle, "input-default-bindings", "yes")
        mpv.mpv_set_option_string(handle, "input-vo-keyboard", "yes")
        mpv.mpv_set_option_string(handle, "osc", "yes")
        mpv.mpv_set_option_string(handle, "hwdec", "auto-safe")
        mpv.mpv_set_option_string(handle, "ytdl", "no")
    }

    /**
     * Tear down the current mpv context if (and only if) it is still
     * attached to the supplied window handle. Used by the Compose
     * surface's `onDispose` so a re-mount with a new canvas resets
     * engine state and the next [open] correctly waits for the new
     * canvas's [attachToWindow]. Idempotent - does nothing if the
     * engine has moved on to a different handle.
     */
    fun detachIf(handle: Long) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (embeddedHandle != handle) return@withContext
                eventLoop?.cancel()
                eventLoop = null
                val mpv = lib ?: return@withContext
                ctx?.let { runCatching { mpv.mpv_terminate_destroy(it) } }
                ctx = mpv.mpv_create()
                ctx?.let { applyDefaultOptions(mpv, it) }
                embeddedHandle = null
                initialized = false
            }
        }
    }

    /**
     * Bind mpv's video output to a native window (HWND/XID/NSView). Must
     * be called **before** the first [open] for embedded rendering to
     * work - wid is a pre-init option in libmpv. If called after
     * initialize, the handle is reset and a fresh init runs.
     */
    suspend fun attachToWindow(handle: Long) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        if (initialized) {
            // Tear down and rebuild so the wid option takes effect.
            ctx?.let { runCatching { mpv.mpv_terminate_destroy(it) } }
            initialized = false
            ctx = mpv.mpv_create() ?: return@withContext
            applyDefaultOptions(mpv, ctx!!)
        }
        embeddedHandle = handle
        ctx?.let { mpv.mpv_set_option_string(it, "wid", handle.toString()) }
        ensureInitialized()
    }

    /**
     * Standalone fallback - when no surface attaches, mpv opens its own
     * window via `force-window=yes` so the user still sees video.
     */
    private fun ensureInitialized() {
        if (initialized) return
        val mpv = lib ?: return
        val handle = ctx ?: return
        if (embeddedHandle == null) {
            mpv.mpv_set_option_string(handle, "force-window", "yes")
        } else {
            mpv.mpv_set_option_string(handle, "force-window", "no")
        }
        val rc = mpv.mpv_initialize(handle)
        if (rc < 0) {
            emit(
                DesktopPlaybackEngineEvent.Error(
                    code = "MPV_INIT_FAILED",
                    message = "mpv_initialize failed with code $rc",
                ),
            )
            return
        }
        initialized = true
        // Subscribe to libmpv's own log stream at warn+ - these land in
        // Sentry breadcrumbs alongside Torve's own diagnostics.
        runCatching { mpv.mpv_request_log_messages(handle, "warn") }
        // Restore last-used audio device, scoped to MPV by storing
        // outputName = "mpv" in the shared audio-device prefs file.
        runCatching {
            com.torve.desktop.vlc.AudioDevicePreferences.getLastDevice()
                ?.takeIf { it.outputName == AUDIO_OUTPUT_TAG }
                ?.deviceId
                ?.takeIf { it.isNotBlank() }
                ?.let { id -> mpv.mpv_set_property_string(handle, "audio-device", id) }
        }
        startEventPump(handle, mpv)
    }

    override suspend fun open(
        session: DesktopPlaybackSession,
        autoPlay: Boolean,
        startupTrace: PlaybackStartupTrace?,
        resumePositionMs: Long?,
    ) = withContext(Dispatchers.IO) {
        // Wait briefly for the Compose surface to attach so we init mpv
        // with a `wid` and paint into the canvas. Without this, racing
        // open() against the surface mount pops mpv's own window. Bounded
        // so a missing/broken surface eventually falls back to standalone
        // rather than deadlocking the player pipeline.
        if (!initialized && embeddedHandle == null) {
            val deadline = System.currentTimeMillis() + 3000
            while (embeddedHandle == null && System.currentTimeMillis() < deadline) {
                delay(50)
            }
        }
        if (!initialized) ensureInitialized()
        val handle = ctx ?: throw IllegalStateException("MPV not initialised. Call probeRuntime first.")
        val mpv = lib ?: throw IllegalStateException("libmpv binding missing")
        val url = session.resolvedUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No resolved URL in session")

        currentMediaPath = url
        emit(
            DesktopPlaybackEngineEvent.Opening(
                mediaPath = url,
                autoPlay = autoPlay,
                appliedHeaderCount = session.requestHeaders.size,
            ),
        )

        // Apply request headers via the http-header-fields property.
        // mpv expects a comma-separated list of `Name: value` strings.
        val headers = session.requestHeaders
        if (headers.isNotEmpty()) {
            val joined = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
            mpv.mpv_set_property_string(handle, "http-header-fields", joined)
        }

        // Resume position via --start, in seconds with 3-decimal precision.
        resumePositionMs?.takeIf { it > 0 }?.let { ms ->
            val sec = ms / 1000.0
            mpv.mpv_set_property_string(handle, "start", "%.3f".format(sec))
        }

        // Apply an initial pause when autoPlay is false. mpv's "pause"
        // property is a flag; "yes"/"no" works.
        mpv.mpv_set_property_string(handle, "pause", if (autoPlay) "no" else "yes")

        // Issue the loadfile command - replaces any current media.
        val rc = mpv.mpv_command(handle, arrayOf("loadfile", url, "replace", null))
        if (rc < 0) {
            emit(
                DesktopPlaybackEngineEvent.Error(
                    code = "MPV_LOADFILE_FAILED",
                    message = "mpv loadfile returned $rc",
                ),
            )
            return@withContext
        }
        startupTrace?.mark("MPV loadfile dispatched")
    }

    override suspend fun play() = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "pause", "no")
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "pause", "yes")
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_command(handle, arrayOf("stop", null))
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_command(handle, arrayOf("stop", null))
        emit(DesktopPlaybackEngineEvent.Closed("Closed by desktop player."))
    }

    /**
     * Set output volume in mpv's native 0-100 scale. Soft-volume above 100
     * is supported but Torve clamps to 0..100 to match the VLC slider.
     */
    suspend fun setVolume(percent: Int) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        val clamped = percent.coerceIn(0, 100)
        mpv.mpv_set_property_string(handle, "volume", clamped.toString())
    }

    /** Read mpv's current volume; returns null if the property isn't yet readable. */
    suspend fun getVolume(): Int? = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext null
        val handle = ctx ?: return@withContext null
        mpv.mpv_get_property_string(handle, "volume")?.toDoubleOrNull()?.toInt()
    }

    suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "mute", if (muted) "yes" else "no")
    }

    suspend fun setVideoAspectOverride(ratio: String?) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "video-aspect-override", ratio ?: "-1")
    }

    /**
     * Add an external subtitle file and select it. Used by the drag-drop
     * pipeline. Path is escaped only by mpv's own argv parser since we
     * pass it via mpv_command's argv array (not the string form).
     */
    suspend fun addSubtitle(path: String) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_command(handle, arrayOf("sub-add", path, "select", null))
    }

    /** Subtitle delay in seconds. Positive = later, negative = earlier. */
    suspend fun setSubtitleDelay(seconds: Double) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "sub-delay", "%.3f".format(seconds))
    }

    suspend fun getSubtitleDelay(): Double? = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext null
        val handle = ctx ?: return@withContext null
        mpv.mpv_get_property_string(handle, "sub-delay")?.toDoubleOrNull()
    }

    /**
     * Comma-separated ISO-639 codes (e.g. "eng,deu"). mpv consults
     * `alang`/`slang` when picking the default audio/subtitle track for
     * the next loaded file. Setting them as runtime properties affects
     * subsequent [open] calls.
     */
    suspend fun setPreferredLanguages(audio: String?, subtitle: String?) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        audio?.takeIf { it.isNotBlank() }?.let {
            mpv.mpv_set_property_string(handle, "alang", it)
        }
        subtitle?.takeIf { it.isNotBlank() }?.let {
            mpv.mpv_set_property_string(handle, "slang", it)
        }
    }

    /** Absolute seek in seconds. mpv's `time-pos` property accepts a double. */
    suspend fun seekTo(seconds: Double) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_command(handle, arrayOf("seek", "%.3f".format(seconds), "absolute", null))
    }

    data class MpvTrack(
        val id: Int,
        val type: String,
        val title: String?,
        val lang: String?,
        val selected: Boolean,
    )

    /**
     * Read the current list of audio + subtitle tracks. Parses mpv's
     * `track-list` property - returned as a JSON array. We do a minimal
     * regex-based parse rather than pull in a JSON library because the
     * structure is fixed and well-known.
     */
    suspend fun listTracks(): List<MpvTrack> = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext emptyList()
        val handle = ctx ?: return@withContext emptyList()
        readTracks(mpv, handle)
    }

    private fun readTracks(mpv: LibMpv, handle: Pointer): List<MpvTrack> {
        val raw = mpv.mpv_get_property_string(handle, "track-list") ?: return emptyList()
        return parseTrackList(raw)
    }

    /**
     * Force-refresh the [tracks] StateFlow with a synchronous read of the
     * current track-list. The pump's property-change observer is the
     * usual driver, but mpv occasionally skips the *initial* change
     * notification - this gives the UI a way to re-poll on demand
     * (e.g. when a menu opens) so the user never sees an empty list
     * during a session that actually has tracks.
     */
    suspend fun refreshTracksNow() = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        runCatching { _tracks.value = readTracks(mpv, handle) }
    }

    suspend fun selectAudioTrack(id: Int?) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "aid", id?.toString() ?: "no")
    }

    suspend fun selectSubtitleTrack(id: Int?) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "sid", id?.toString() ?: "no")
    }

    data class MpvAudioDevice(
        val name: String,
        val description: String?,
    )

    /** List audio devices mpv can route to. Parses `audio-device-list` JSON. */
    suspend fun listAudioDevices(): List<MpvAudioDevice> = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext emptyList()
        val handle = ctx ?: return@withContext emptyList()
        val raw = mpv.mpv_get_property_string(handle, "audio-device-list") ?: return@withContext emptyList()
        parseAudioDevices(raw)
    }

    /** Switch active audio device and persist for next launch. */
    suspend fun setAudioDevice(name: String) = withContext(Dispatchers.IO) {
        val mpv = lib ?: return@withContext
        val handle = ctx ?: return@withContext
        mpv.mpv_set_property_string(handle, "audio-device", name)
        com.torve.desktop.vlc.AudioDevicePreferences.setLastDevice(
            outputName = AUDIO_OUTPUT_TAG,
            deviceId = name,
        )
    }

    /**
     * Push a freshly-fetched device list into the StateFlow. Used by the
     * shell to seed the flow on entry - mpv doesn't always emit a change
     * event for the *initial* value, so we read once explicitly.
     */
    internal fun refreshAudioDevicesNow(list: List<MpvAudioDevice>) {
        _audioDevices.value = list
    }

    private fun parseAudioDevices(json: String): List<MpvAudioDevice> {
        val out = mutableListOf<MpvAudioDevice>()
        val objects = Regex("\\{[^{}]*\\}").findAll(json)
        for (match in objects) {
            val body = match.value
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: continue
            val description = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            out += MpvAudioDevice(name = name, description = description)
        }
        return out
    }

    private fun parseTrackList(json: String): List<MpvTrack> {
        // mpv's track-list is a JSON array of objects with stable keys:
        // `id` (int), `type`, `title`, `lang`, `selected` (bool).
        // Walk the string with a brace-depth counter so nested objects
        // (some mpv builds embed `albumart`/`decoder-desc` substructures)
        // don't truncate the slice. Earlier regex `\{[^{}]*\}` failed on
        // those - explaining "0 subs" when 54 were really there.
        val out = mutableListOf<MpvTrack>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in json.indices) {
            val c = json[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val body = json.substring(start, i + 1)
                        parseTrack(body)?.let(out::add)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private fun parseTrack(body: String): MpvTrack? {
        val id = Regex("\"id\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val type = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: return null
        if (type != "audio" && type != "sub") return null
        val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        val lang = Regex("\"lang\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        val selected = Regex("\"selected\"\\s*:\\s*true").containsMatchIn(body)
        return MpvTrack(id = id, type = type, title = title, lang = lang, selected = selected)
    }

    override fun dispose() {
        eventLoop?.cancel()
        val mpv = lib
        val handle = ctx
        if (mpv != null && handle != null) {
            runCatching { mpv.mpv_terminate_destroy(handle) }
        }
        ctx = null
        lib = null
        embeddedHandle = null
        initialized = false
        scope.cancel()
    }

    /**
     * Pump events from libmpv into our [events] flow. Polls
     * `mpv_wait_event` with a short timeout so cancellation lands
     * quickly. Most events we just translate to telemetry; only a
     * handful (`property-change` for time-pos / pause / etc.) drive
     * UI state - those are added incrementally when needed.
     */
    private fun startEventPump(handle: Pointer, mpv: LibMpv) {
        eventLoop?.cancel()
        eventLoop = scope.launch {
            mpv.mpv_observe_property(handle, 1L, "time-pos", LibMpv.FORMAT_DOUBLE)
            mpv.mpv_observe_property(handle, 2L, "duration", LibMpv.FORMAT_DOUBLE)
            mpv.mpv_observe_property(handle, 3L, "pause", LibMpv.FORMAT_FLAG)
            // Observe track-list as STRING - we re-fetch via property
            // get on change so we don't have to decode the union node.
            mpv.mpv_observe_property(handle, 4L, "track-list", LibMpv.FORMAT_STRING)
            mpv.mpv_observe_property(handle, 5L, "volume", LibMpv.FORMAT_DOUBLE)
            mpv.mpv_observe_property(handle, 6L, "mute", LibMpv.FORMAT_FLAG)
            mpv.mpv_observe_property(handle, 7L, "audio-device-list", LibMpv.FORMAT_STRING)
            var lastPos: Double? = null
            var lastDur: Double? = null
            while (isActive) {
                val ev = mpv.mpv_wait_event(handle, 0.5) ?: continue
                when (ev.event_id) {
                    LibMpv.EVENT_SHUTDOWN -> {
                        emit(DesktopPlaybackEngineEvent.Closed("mpv shutdown"))
                        break
                    }
                    LibMpv.EVENT_LOG_MESSAGE -> {
                        ev.data?.let { p ->
                            runCatching {
                                val msg = MpvEventLogMessage(p)
                                forwardLogToSentry(msg)
                            }
                        }
                    }
                    LibMpv.EVENT_END_FILE -> {
                        // Decode the union to distinguish natural eof
                        // (next-episode-eligible) from error / stop /
                        // quit / redirect, which need different handling.
                        val reason = ev.data?.let { runCatching { MpvEventEndFile(it).reason }.getOrNull() }
                            ?: LibMpv.END_FILE_REASON_EOF
                        when (reason) {
                            LibMpv.END_FILE_REASON_ERROR -> emit(
                                DesktopPlaybackEngineEvent.Error(
                                    code = "MPV_END_FILE_ERROR",
                                    message = "Playback ended with mpv error",
                                ),
                            )
                            LibMpv.END_FILE_REASON_REDIRECT -> {
                                // mpv chained to another URL; not a real end.
                                // Skip Closed so we don't tear down the session.
                            }
                            LibMpv.END_FILE_REASON_STOP,
                            LibMpv.END_FILE_REASON_QUIT -> emit(
                                DesktopPlaybackEngineEvent.Stopped("Stopped by user"),
                            )
                            else -> emit(DesktopPlaybackEngineEvent.Closed("Playback finished"))
                        }
                    }
                    LibMpv.EVENT_FILE_LOADED -> {
                        emit(DesktopPlaybackEngineEvent.Playing(mediaPath = currentMediaPath))
                        // Pull the initial track list so the menus
                        // populate as soon as a file loads.
                        runCatching { _tracks.value = readTracks(mpv, handle) }
                    }
                    LibMpv.EVENT_PLAYBACK_RESTART -> {
                        emit(DesktopPlaybackEngineEvent.FirstFrame(mediaPath = currentMediaPath))
                    }
                    LibMpv.EVENT_PROPERTY_CHANGE -> {
                        val name = ev.data?.let { runCatching { MpvEventProperty(it).name }.getOrNull() }
                        when (name) {
                            "track-list" -> runCatching { _tracks.value = readTracks(mpv, handle) }
                            "volume" -> {
                                runCatching {
                                    mpv.mpv_get_property_string(handle, "volume")
                                        ?.toDoubleOrNull()
                                        ?.toInt()
                                        ?.let { _volume.value = it }
                                }
                            }
                            "mute" -> {
                                runCatching {
                                    val v = mpv.mpv_get_property_string(handle, "mute")
                                    _muted.value = v == "yes" || v == "1" || v == "true"
                                }
                            }
                            "audio-device-list" -> {
                                runCatching {
                                    val raw = mpv.mpv_get_property_string(handle, "audio-device-list")
                                    if (raw != null) _audioDevices.value = parseAudioDevices(raw)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
                // Pull positions from properties - cheaper than decoding
                // the property-change union and good enough for a 1Hz UI.
                runCatching {
                    val posStr = mpv.mpv_get_property_string(handle, "time-pos")
                    val durStr = mpv.mpv_get_property_string(handle, "duration")
                    val pos = posStr?.toDoubleOrNull()
                    val dur = durStr?.toDoubleOrNull()
                    if (pos != lastPos || dur != lastDur) {
                        lastPos = pos
                        lastDur = dur
                        _events.tryEmit(
                            DesktopPlaybackEngineEvent.Position(
                                positionSeconds = pos,
                                durationSeconds = dur,
                            ),
                        )
                    }
                }
                // Brief yield so cancellation lands fast.
                delay(50)
            }
        }
    }

    private fun emit(event: DesktopPlaybackEngineEvent) {
        _events.tryEmit(event)
    }

    /**
     * Map mpv's textual log level to a Sentry level and add a
     * breadcrumb. Trim the trailing newline mpv always appends so the
     * crumb message stays on one line.
     */
    private fun forwardLogToSentry(msg: MpvEventLogMessage) {
        val text = msg.text?.trimEnd() ?: return
        if (text.isBlank()) return
        val level = when (msg.level?.lowercase()) {
            "fatal", "error" -> io.sentry.SentryLevel.ERROR
            "warn", "warning" -> io.sentry.SentryLevel.WARNING
            "info", "v", "debug", "trace" -> io.sentry.SentryLevel.INFO
            else -> io.sentry.SentryLevel.INFO
        }
        com.torve.desktop.diagnostics.SentryBootstrap.breadcrumb(
            category = "mpv",
            message = text.take(500),
            level = level,
            data = buildMap {
                msg.prefix?.let { put("prefix", it) }
                msg.level?.let { put("mpv_level", it) }
            },
        )
    }
}
