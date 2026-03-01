package com.streamvault.android.sync.storage

import android.content.Context
import java.util.UUID

class InstallationIdStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateInstallationId(): String {
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    private companion object {
        const val PREFS_NAME = "torve_installation"
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}

