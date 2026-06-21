package com.torve.desktop.vlc

import com.torve.desktop.platform.desktopDataDir
import java.io.File
import java.util.Properties

/**
 * Last-selected audio output device, persisted to a Properties file
 * under `desktopDataDir()`. The VLC player session calls
 * [setLastDevice] every time the user picks a device through the chrome,
 * and reads [getLastDevice] right after bootstrap to restore it on the
 * next launch.
 *
 * Two fields stored:
 *  - `outputName` - e.g. `mmdevice`, `directsound`, `pulse`
 *  - `deviceId` - backend-specific GUID / ALSA name / Core Audio uid
 *
 * Both must match for the restore to fire - devices vanish/rename when
 * the user unplugs hardware, so we silently skip restore on a miss
 * rather than half-applying.
 */
object AudioDevicePreferences {

    private const val FILE_NAME = "audio_device_prefs.properties"
    private const val KEY_OUTPUT = "outputName"
    private const val KEY_DEVICE = "deviceId"

    private val file: File by lazy { File(desktopDataDir(), FILE_NAME) }

    @Volatile
    private var cached: Properties? = null

    data class Saved(val outputName: String, val deviceId: String)

    fun getLastDevice(): Saved? {
        val props = read()
        val out = props.getProperty(KEY_OUTPUT)?.takeIf { it.isNotBlank() } ?: return null
        val dev = props.getProperty(KEY_DEVICE).orEmpty()
        return Saved(outputName = out, deviceId = dev)
    }

    fun setLastDevice(outputName: String, deviceId: String) {
        val props = read()
        props.setProperty(KEY_OUTPUT, outputName)
        props.setProperty(KEY_DEVICE, deviceId)
        write(props)
    }

    fun clear() {
        val props = read()
        props.remove(KEY_OUTPUT)
        props.remove(KEY_DEVICE)
        write(props)
    }

    private fun read(): Properties {
        cached?.let { return it }
        val props = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        cached = props
        return props
    }

    private fun write(props: Properties) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "Torve audio device preferences") }
            cached = props
        }.onFailure { t ->
            println("TORVE VLC | audio prefs write failed: ${t.message}")
        }
    }
}
