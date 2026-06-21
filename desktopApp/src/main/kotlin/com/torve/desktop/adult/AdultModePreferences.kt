package com.torve.desktop.adult

import com.torve.domain.diagnostics.DiagnosticsRedactor
import com.torve.platform.TorveRuntimeDebug
import com.torve.desktop.platform.desktopDataDir
import java.io.File
import java.util.Properties

/**
 * Local desktop toggle for the adult catalog. Lives in its own
 * properties file so the existing shared-server `ContentPolicyState`
 * (which is account/age-band gated) doesn't get confused.
 *
 * When enabled, the desktop nav rail surfaces an "Adult" entry that
 * routes to a TMDB-discover/search page with `include_adult=true`.
 * Playback still goes through the standard source picker; this toggle
 * is a *catalog* gate, not a content-policy override for the rest of
 * the app.
 */
object AdultModePreferences {

    private const val FILE_NAME = "adult_mode.properties"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INDEXER_URL = "indexer_url"
    private const val KEY_INDEXER_KEY = "indexer_api_key"
    private const val KEY_CATEGORY = "indexer_category"

    /** scenenzbs convention: 6010 = XXX/Movies (single-movie releases). */
    const val DEFAULT_CATEGORY: String = "6010"

    private val file: File by lazy { File(desktopDataDir(), FILE_NAME) }

    @Volatile
    private var cached: Properties? = null

    fun isEnabled(): Boolean =
        load().getProperty(KEY_ENABLED, "false").toBooleanStrictOrNull() ?: false

    fun setEnabled(enabled: Boolean) {
        val props = load()
        props.setProperty(KEY_ENABLED, enabled.toString())
        save(props)
    }

    fun getIndexerUrl(): String = load().getProperty(KEY_INDEXER_URL, "").orEmpty()
    fun setIndexerUrl(url: String) {
        val props = load()
        props.setProperty(KEY_INDEXER_URL, url.trim())
        save(props)
    }

    fun getIndexerApiKey(): String = load().getProperty(KEY_INDEXER_KEY, "").orEmpty()
    fun setIndexerApiKey(key: String) {
        val props = load()
        props.setProperty(KEY_INDEXER_KEY, key.trim())
        save(props)
    }

    fun getCategory(): String = load().getProperty(KEY_CATEGORY, DEFAULT_CATEGORY).orEmpty()
        .ifBlank { DEFAULT_CATEGORY }
    fun setCategory(cat: String) {
        val props = load()
        props.setProperty(KEY_CATEGORY, cat.trim().ifBlank { DEFAULT_CATEGORY })
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
            file.outputStream().use { props.store(it, "Torve adult-mode preferences") }
            cached = props
        }.onFailure { t ->
            if (TorveRuntimeDebug.verboseLoggingEnabled) {
                println("TORVE ADULT | preferences write failed: ${t::class.simpleName} ${DiagnosticsRedactor.redact(t.message)}")
            }
        }
    }
}
