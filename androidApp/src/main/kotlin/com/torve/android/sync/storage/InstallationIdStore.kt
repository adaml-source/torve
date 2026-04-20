package com.torve.android.sync.storage

import android.content.Context
import java.util.UUID

class InstallationIdStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateInstallationId(): String {
        val androidId = readAndroidId()
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank() && !isLegacyHardwareBoundInstallationId(existing, androidId)) {
            return existing
        }

        // installation_id must be app-scoped and change on reinstall/data clear.
        // Migrate any old ANDROID_ID-derived values to a true random installation UUID.
        val generated = newInstallationId()
        prefs.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    private fun readAndroidId(): String? {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PREFS_NAME = "torve_installation"
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}

internal fun isLegacyHardwareBoundInstallationId(existing: String, androidId: String?): Boolean {
    val stableId = androidId?.takeIf { it.isNotBlank() } ?: return false
    return existing == stableId || existing == "a_$stableId"
}

internal fun newInstallationId(): String = UUID.randomUUID().toString()
