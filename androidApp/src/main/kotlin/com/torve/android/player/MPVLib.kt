package com.torve.android.player

import android.content.Context
import android.view.Surface
import `is`.xyz.mpv.MPVLib as UpstreamMPVLib

/**
 * JNI bridge to libmpv. Native methods map to mpv's client API.
 * Requires libmpv .so files in jniLibs/ (arm64-v8a, armeabi-v7a, x86_64).
 */
object MPVLib {

    private var isLoaded = false
    private var isInitialized = false

    /**
     * Try to load native libraries. Returns true if successful.
     */
    fun tryLoad(): Boolean {
        if (isLoaded) return true
        return try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("avutil")
            System.loadLibrary("swresample")
            System.loadLibrary("swscale")
            System.loadLibrary("avcodec")
            System.loadLibrary("avformat")
            System.loadLibrary("avfilter")
            System.loadLibrary("avdevice")
            System.loadLibrary("mpv")
            System.loadLibrary("player")
            isLoaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun isAvailable(): Boolean = isLoaded
    fun isInitialized(): Boolean = isLoaded && isInitialized

    // --- Initialization ---

    fun create(context: Context) = UpstreamMPVLib.create(context)
    fun init() {
        UpstreamMPVLib.init()
        isInitialized = true
    }

    fun destroy() {
        if (!isInitialized()) return
        UpstreamMPVLib.destroy()
        isInitialized = false
    }

    fun setOptionString(name: String, value: String): Int = UpstreamMPVLib.setOptionString(name, value)

    // --- Surface ---

    fun attachSurface(surface: Surface) {
        if (!isInitialized()) return
        UpstreamMPVLib.attachSurface(surface)
    }

    fun detachSurface() {
        if (!isInitialized()) return
        UpstreamMPVLib.detachSurface()
    }

    // --- Playback control ---

    fun command(cmd: Array<String>) = UpstreamMPVLib.command(cmd)

    fun loadFile(url: String) {
        command(arrayOf("loadfile", url))
    }

    fun play() {
        setPropertyBoolean("pause", false)
    }

    fun pause() {
        setPropertyBoolean("pause", true)
    }

    fun stop() {
        command(arrayOf("stop"))
    }

    fun seek(positionSec: Double) {
        command(arrayOf("seek", positionSec.toString(), "absolute"))
    }

    fun seekRelative(deltaSec: Double) {
        command(arrayOf("seek", deltaSec.toString(), "relative"))
    }

    // --- Properties ---

    fun setPropertyBoolean(name: String, value: Boolean) = UpstreamMPVLib.setPropertyBoolean(name, value)
    fun setPropertyInt(name: String, value: Int) = UpstreamMPVLib.setPropertyInt(name, value)
    fun setPropertyDouble(name: String, value: Double) = UpstreamMPVLib.setPropertyDouble(name, value)
    fun setPropertyString(name: String, value: String) = UpstreamMPVLib.setPropertyString(name, value)

    fun getPropertyBoolean(name: String): Boolean = UpstreamMPVLib.getPropertyBoolean(name)
    fun getPropertyInt(name: String): Int = UpstreamMPVLib.getPropertyInt(name)
    fun getPropertyDouble(name: String): Double = UpstreamMPVLib.getPropertyDouble(name)
    fun getPropertyString(name: String): String? = UpstreamMPVLib.getPropertyString(name)

    // --- Observe properties (callbacks dispatched to EventThread) ---

    fun observeProperty(name: String, format: Int) = UpstreamMPVLib.observeProperty(name, format)

    // MPV property format constants
    const val MPV_FORMAT_NONE = 0
    const val MPV_FORMAT_STRING = 1
    const val MPV_FORMAT_FLAG = 3
    const val MPV_FORMAT_INT64 = 4
    const val MPV_FORMAT_DOUBLE = 5

    // --- Track info ---

    data class Track(
        val id: Int,
        val type: String, // "audio", "sub", "video"
        val title: String?,
        val language: String?,
        val codec: String?,
        val isDefault: Boolean,
        val isSelected: Boolean,
    )

    fun getTracks(): List<Track> {
        if (!isLoaded) return emptyList()
        val count = try { getPropertyInt("track-list/count") } catch (_: Exception) { 0 }
        val selectedAudioTrackId = try {
            getPropertyInt("aid")
        } catch (_: Exception) {
            getPropertyString("aid")?.toIntOrNull() ?: -1
        }
        val tracks = mutableListOf<Track>()
        for (i in 0 until count) {
            try {
                val type = getPropertyString("track-list/$i/type") ?: continue
                val trackId = getPropertyInt("track-list/$i/id")
                val selectedByTrackList = try { getPropertyBoolean("track-list/$i/selected") } catch (_: Exception) { false }
                tracks.add(
                    Track(
                        id = trackId,
                        type = type,
                        title = getPropertyString("track-list/$i/title"),
                        language = getPropertyString("track-list/$i/lang"),
                        codec = getPropertyString("track-list/$i/codec"),
                        isDefault = try { getPropertyBoolean("track-list/$i/default") } catch (_: Exception) { false },
                        isSelected = selectedByTrackList || (type == "audio" && trackId == selectedAudioTrackId),
                    ),
                )
            } catch (_: Exception) {
                // Skip malformed track entries
            }
        }
        return tracks
    }

    fun selectAudioTrack(trackId: Int) {
        setPropertyString("aid", trackId.toString())
    }

    fun selectSubtitleTrack(trackId: Int) {
        setPropertyString("sid", trackId.toString())
    }

    fun disableSubtitles() {
        setPropertyString("sid", "no")
    }

    // --- Event listener ---

    interface EventObserver {
        fun onPropertyChange(property: String, value: Any?)
        fun onEvent(eventId: Int)
    }

    private val observers = mutableListOf<EventObserver>()

    fun addObserver(observer: EventObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: EventObserver) {
        observers.remove(observer)
    }

    // Called from JNI
    @JvmStatic
    fun dispatchEventProperty(property: String, value: Any?) {
        observers.forEach { it.onPropertyChange(property, value) }
    }

    @JvmStatic
    fun dispatchEvent(eventId: Int) {
        observers.forEach { it.onEvent(eventId) }
    }
}
