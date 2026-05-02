package com.torve.desktop.updates

import com.torve.desktop.platform.desktopDataDir
import java.io.File
import java.util.Properties

/**
 * Tiny preferences store for the update checker. One file
 * (`update_prefs.properties`) under `desktopDataDir()`. Currently holds:
 *
 *  - `autoCheckOnLaunch` (default true) - whether [UpdateChecker.check] is
 *    fired automatically the first time the shell composes.
 *
 * Properties because there's literally one boolean today; promoting to
 * JSON / SQLDelight when this grows past a handful of fields.
 */
object UpdateCheckerPreferences {

    private const val FILE_NAME = "update_prefs.properties"
    private const val KEY_AUTO_CHECK = "autoCheckOnLaunch"

    private val file: File by lazy {
        File(desktopDataDir(), FILE_NAME)
    }

    @Volatile
    private var cached: Properties? = null

    fun isAutoCheckEnabled(): Boolean = read().getProperty(KEY_AUTO_CHECK, "true").toBooleanStrictOrNull() ?: true

    fun setAutoCheckEnabled(enabled: Boolean) {
        val props = read()
        props.setProperty(KEY_AUTO_CHECK, enabled.toString())
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
            file.outputStream().use { props.store(it, "Torve update checker preferences") }
            cached = props
        }.onFailure { t ->
            println("TORVE UPDATE | preferences write failed: ${t.message}")
        }
    }
}
