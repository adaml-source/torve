package com.torve.android.sync.storage

import android.content.Context
import android.provider.Settings
import java.util.UUID

class InstallationIdStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateInstallationId(): String {
        // Return existing ID if already persisted (backwards-compatible)
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) return existing

        // For new installations, prefer ANDROID_ID — it survives app reinstalls
        // (stable per app-signing-key + user + device on Android 8+).
        // Fall back to a random UUID for emulators or restricted environments.
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        val generated = if (!androidId.isNullOrBlank()) "a_$androidId" else UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    private companion object {
        const val PREFS_NAME = "torve_installation"
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}

