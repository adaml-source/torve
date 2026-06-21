package com.torve.domain.recording

import com.torve.domain.repository.PreferencesRepository
import com.torve.presentation.settings.SettingsViewModel

/**
 * Typed wrapper for the user-facing recording preferences. Reads from
 * the prefs repo on demand (no in-memory cache; settings UI updates
 * land immediately the next time something asks).
 *
 * Defaults are conservative:
 *   - default duration: 120 minutes (matches the previous hardcoded "2h")
 *   - pre-roll:  1 minute  (compensate for stations that start early)
 *   - post-roll: 5 minutes (catch sports overruns / late shows)
 *   - max concurrent: 3 (sane upper bound for a typical home connection)
 *
 * `defaultDurationMin == 0` is the sentinel for "Until I stop" -- the
 * caller should map that to a very long endMs (e.g. 24 hours) so the
 * recording runs until the user explicitly hits Stop.
 */
data class RecordingPreferences(
    val defaultDurationMin: Int = 120,
    val preRollMin: Int = 1,
    val postRollMin: Int = 5,
    val maxConcurrent: Int = 3,
) {
    /** Sentinel for "Until I stop" -- mapped to 24h server-side. */
    val isUnboundedDefaultDuration: Boolean get() = defaultDurationMin == 0

    val defaultDurationMs: Long
        get() = if (isUnboundedDefaultDuration) UNBOUNDED_DURATION_MS
        else defaultDurationMin * 60_000L

    val preRollMs: Long get() = preRollMin * 60_000L
    val postRollMs: Long get() = postRollMin * 60_000L

    companion object {
        const val UNBOUNDED_DURATION_MS: Long = 24L * 60L * 60L * 1000L

        suspend fun load(prefsRepo: PreferencesRepository): RecordingPreferences =
            RecordingPreferences(
                defaultDurationMin = prefsRepo
                    .getString(SettingsViewModel.KEY_RECORDING_DEFAULT_DURATION_MIN)
                    ?.toIntOrNull() ?: 120,
                preRollMin = prefsRepo
                    .getString(SettingsViewModel.KEY_RECORDING_PRE_ROLL_MIN)
                    ?.toIntOrNull() ?: 1,
                postRollMin = prefsRepo
                    .getString(SettingsViewModel.KEY_RECORDING_POST_ROLL_MIN)
                    ?.toIntOrNull() ?: 5,
                maxConcurrent = prefsRepo
                    .getString(SettingsViewModel.KEY_RECORDING_MAX_CONCURRENT)
                    ?.toIntOrNull() ?: 3,
            )

        suspend fun save(prefsRepo: PreferencesRepository, prefs: RecordingPreferences) {
            prefsRepo.setString(
                SettingsViewModel.KEY_RECORDING_DEFAULT_DURATION_MIN,
                prefs.defaultDurationMin.toString(),
            )
            prefsRepo.setString(
                SettingsViewModel.KEY_RECORDING_PRE_ROLL_MIN,
                prefs.preRollMin.toString(),
            )
            prefsRepo.setString(
                SettingsViewModel.KEY_RECORDING_POST_ROLL_MIN,
                prefs.postRollMin.toString(),
            )
            prefsRepo.setString(
                SettingsViewModel.KEY_RECORDING_MAX_CONCURRENT,
                prefs.maxConcurrent.toString(),
            )
        }
    }
}
