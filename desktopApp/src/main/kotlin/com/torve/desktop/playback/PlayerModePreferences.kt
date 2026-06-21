package com.torve.desktop.playback

import com.torve.desktop.platform.desktopDataDir
import java.io.File
import java.util.Properties

/**
 * Persists the user's chosen [DesktopPlayerMode] to a properties file
 * under `desktopDataDir()`. Read at app startup in Main.kt to decide
 * which playback engine to instantiate; written from Settings.
 *
 * Properties file because there is exactly one key today; promote to
 * something richer once it grows.
 */
object PlayerModePreferences {

    private const val FILE_NAME = "player_mode.properties"
    private const val KEY_MODE = "mode"

    private val file: File by lazy { File(desktopDataDir(), FILE_NAME) }

    @Volatile
    private var cached: Properties? = null

    fun read(): DesktopPlayerMode =
        DesktopPlayerMode.fromSettingsKey(load().getProperty(KEY_MODE))

    fun write(mode: DesktopPlayerMode) {
        val props = load()
        props.setProperty(KEY_MODE, mode.settingsKey)
        save(props)
    }

    private fun load(): Properties {
        cached?.let { return it }
        val props = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        cached = props
        return props
    }

    private fun save(props: Properties) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "Torve desktop player mode") }
            cached = props
        }.onFailure { t ->
            println("TORVE PLAYER | preferences write failed: ${t.message}")
        }
    }
}
