package com.torve.android.player

import android.content.Context
import android.view.Surface

/**
 * JNI bridge to libmpv. Native methods map to mpv's client API.
 * Requires libmpv .so files in jniLibs/ (arm64-v8a, armeabi-v7a, x86_64).
 */
object MPVLib {

    private var isLoaded = false

    /**
     * Try to load native libraries. Returns true if successful.
     */
    fun tryLoad(): Boolean {
        if (isLoaded) return true
        return try {
            System.loadLibrary("mpv")
            isLoaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun isAvailable(): Boolean = isLoaded

    // --- Initialization ---

    external fun create(context: Context)
    external fun init()
    external fun destroy()

    // --- Surface ---

    external fun attachSurface(surface: Surface)
    external fun detachSurface()

    // --- Playback control ---

    external fun command(cmd: Array<String>)

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

    external fun setPropertyBoolean(name: String, value: Boolean)
    external fun setPropertyInt(name: String, value: Int)
    external fun setPropertyDouble(name: String, value: Double)
    external fun setPropertyString(name: String, value: String)

    external fun getPropertyBoolean(name: String): Boolean
    external fun getPropertyInt(name: String): Int
    external fun getPropertyDouble(name: String): Double
    external fun getPropertyString(name: String): String?

    // --- Observe properties (callbacks dispatched to EventThread) ---

    external fun observeProperty(name: String, format: Int)

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
        val tracks = mutableListOf<Track>()
        for (i in 0 until count) {
            try {
                val type = getPropertyString("track-list/$i/type") ?: continue
                tracks.add(
                    Track(
                        id = getPropertyInt("track-list/$i/id"),
                        type = type,
                        title = getPropertyString("track-list/$i/title"),
                        language = getPropertyString("track-list/$i/lang"),
                        codec = getPropertyString("track-list/$i/codec"),
                        isDefault = try { getPropertyBoolean("track-list/$i/default") } catch (_: Exception) { false },
                        isSelected = try { getPropertyBoolean("track-list/$i/selected") } catch (_: Exception) { false },
                    ),
                )
            } catch (_: Exception) {
                // Skip malformed track entries
            }
        }
        return tracks
    }

    fun selectAudioTrack(trackId: Int) {
        setPropertyInt("aid", trackId)
    }

    fun selectSubtitleTrack(trackId: Int) {
        setPropertyInt("sid", trackId)
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
    fun eventProperty(property: String, value: Any?) {
        observers.forEach { it.onPropertyChange(property, value) }
    }

    // Called from JNI
    @JvmStatic
    fun event(eventId: Int) {
        observers.forEach { it.onEvent(eventId) }
    }
}
