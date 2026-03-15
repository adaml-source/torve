package com.torve.android.player

import android.content.Context
import android.os.Build
import android.util.Log
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

internal enum class LiveAudioRecoveryKind {
    MOBILE_REFERENCE_FIRST_PASS,
    COMPATIBLE_MODE,
    COMPATIBLE_TRACK,
    SOFTWARE_AUDIO,
    STEREO_PCM,
    ENGINE_FALLBACK,
}

internal enum class LivePlayerEngineId(val storageValue: String) {
    MPV("mpv"),
    EXOPLAYER("exoplayer"),
    ;

    companion object {
        fun fromStorage(value: String?): LivePlayerEngineId? = entries.firstOrNull {
            it.storageValue.equals(value, ignoreCase = true)
        }
    }
}

internal enum class LiveAudioTerminalFailureKind {
    EXOPLAYER_AUDIO_UNRECOVERABLE,
}

internal data class LiveAudioPlaybackContext(
    val deviceProfile: String,
    val channelKey: String,
    val streamKey: String,
    val displayName: String,
) {
    val sessionKey: String = "$deviceProfile|$channelKey|$streamKey"

    companion object {
        fun fromChannel(channel: Channel): LiveAudioPlaybackContext {
            val deviceProfile = buildDeviceProfile()
            val channelIdentity = buildString {
                append(channel.playlistId.trim())
                append('|')
                append(channel.tvgId?.trim().orEmpty())
                append('|')
                append(channel.tvgName?.trim().orEmpty())
                append('|')
                append(channel.name.trim())
            }
            val normalizedUrl = channel.url
                .substringBefore('?')
                .trim()
                .lowercase(Locale.ROOT)
            return LiveAudioPlaybackContext(
                deviceProfile = deviceProfile,
                channelKey = fingerprint(channelIdentity.lowercase(Locale.ROOT)),
                streamKey = fingerprint(normalizedUrl),
                displayName = channel.name,
            )
        }

        private fun buildDeviceProfile(): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().trim().lowercase(Locale.ROOT)
            val model = Build.MODEL.orEmpty().trim().lowercase(Locale.ROOT)
            return "$manufacturer|$model|sdk${Build.VERSION.SDK_INT}"
        }

        private fun fingerprint(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

@Serializable
internal data class LiveAudioTrackHint(
    val label: String? = null,
    val language: String? = null,
    val formatKey: String? = null,
    val channelCount: Int? = null,
    val groupIndex: Int? = null,
    val trackIndex: Int? = null,
)

@Serializable
internal data class LiveAudioCompatibilityHint(
    val deviceProfile: String,
    val channelKey: String,
    val streamKey: String,
    val passthroughEnabled: Boolean,
    val preferSurround: Boolean,
    val outputMode: String,
    val recoveryKind: LiveAudioRecoveryKind,
    val preferredEngine: String? = null,
    val preferredTrack: LiveAudioTrackHint? = null,
    val audioSignature: String? = null,
    val softwareAudioRequired: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val successCount: Int = 1,
    val failureCount: Int = 0,
    val lastFailureReason: String? = null,
) {
    fun liveAudioOutputMode(): LiveAudioOutputMode = LiveAudioOutputMode.fromStorage(outputMode)
    fun preferredEngineId(): LivePlayerEngineId? = LivePlayerEngineId.fromStorage(preferredEngine)
}

@Serializable
internal data class LiveAudioTerminalFailureHint(
    val deviceProfile: String,
    val channelKey: String,
    val streamKey: String,
    val preferencesKey: String,
    val selectedMime: String? = null,
    val audioSignature: String? = null,
    val terminalFailureKind: String,
    val finalRecoveryMode: String,
    val finalTuneState: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val failureCount: Int = 1,
    val lastFailureReason: String? = null,
) {
    fun terminalKind(): LiveAudioTerminalFailureKind? = runCatching {
        LiveAudioTerminalFailureKind.valueOf(terminalFailureKind)
    }.getOrNull()
}

internal fun buildLiveAudioPreferencesKey(
    passthroughEnabled: Boolean,
    preferSurround: Boolean,
    outputMode: LiveAudioOutputMode,
): String {
    return buildString {
        append(passthroughEnabled)
        append('|')
        append(preferSurround)
        append('|')
        append(outputMode.storageValue)
    }
}

internal object LiveAudioCompatibilityStore {
    private const val PREF_NAME = "torve_live_audio_compatibility"
    private const val PREF_KEY_HINTS = "live_audio_hints_v1"
    private const val PREF_KEY_TERMINAL_FAILURES = "live_audio_terminal_failures_v1"
    private const val MAX_HINT_COUNT = 48
    private const val MAX_HINT_AGE_MS = 21L * 24L * 60L * 60L * 1000L
    private const val MAX_TERMINAL_FAILURE_COUNT = 48
    private const val MAX_TERMINAL_FAILURE_AGE_MS = 21L * 24L * 60L * 60L * 1000L
    private const val TAG = "ChannelProfile"

    private val json = Json { ignoreUnknownKeys = true }

    private data class SessionState(
        val hint: LiveAudioCompatibilityHint? = null,
        val incompatiblePreferencesKey: String? = null,
        val terminalFailure: LiveAudioTerminalFailureHint? = null,
    )

    private val sessionState = mutableMapOf<String, SessionState>()

    internal fun clearInMemoryState() {
        sessionState.clear()
    }

    fun resolveHint(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
    ): LiveAudioCompatibilityHint? {
        sessionState[playbackContext.sessionKey]?.hint?.let { hint ->
            if (!isExpired(hint)) return hint
            sessionState.remove(playbackContext.sessionKey)
        }

        val persistedHints = loadHints(context)
        val match = persistedHints.firstOrNull { hint ->
            hint.deviceProfile == playbackContext.deviceProfile &&
                hint.channelKey == playbackContext.channelKey &&
                hint.streamKey == playbackContext.streamKey &&
                !isExpired(hint)
        } ?: return null

        Log.d(
            TAG,
            "Resolved playback profile channel=${playbackContext.displayName} " +
                "engine=${match.preferredEngine ?: "none"} recovery=${match.recoveryKind.name} " +
                "failures=${match.failureCount} softwareAudio=${match.softwareAudioRequired}",
        )

        sessionState[playbackContext.sessionKey] = sessionState[playbackContext.sessionKey]
            ?.copy(hint = match)
            ?: SessionState(hint = match)
        return match
    }

    fun resolveTerminalFailure(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
        preferencesKey: String,
    ): LiveAudioTerminalFailureHint? {
        sessionState[playbackContext.sessionKey]?.terminalFailure?.let { hint ->
            if (
                hint.preferencesKey == preferencesKey &&
                !isExpired(hint)
            ) {
                return hint
            }
            if (isExpired(hint)) {
                sessionState.remove(playbackContext.sessionKey)
            }
        }

        val match = loadTerminalFailures(context).firstOrNull { hint ->
            hint.deviceProfile == playbackContext.deviceProfile &&
                hint.channelKey == playbackContext.channelKey &&
                hint.streamKey == playbackContext.streamKey &&
                hint.preferencesKey == preferencesKey &&
                !isExpired(hint)
        } ?: return null

        Log.i(
            TAG,
            "Resolved terminal playback failure channel=${playbackContext.displayName} " +
                "kind=${match.terminalFailureKind} recovery=${match.finalRecoveryMode} " +
                "mime=${match.selectedMime ?: "unknown"} failures=${match.failureCount}",
        )
        sessionState[playbackContext.sessionKey] = sessionState[playbackContext.sessionKey]
            ?.copy(terminalFailure = match)
            ?: SessionState(terminalFailure = match)
        return match
    }

    fun rememberSuccessfulRecovery(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode,
        recoveryKind: LiveAudioRecoveryKind,
        engineId: LivePlayerEngineId?,
        preferredTrack: LiveAudioTrackHint?,
        audioSignature: String?,
        softwareAudioRequired: Boolean = false,
    ): LiveAudioCompatibilityHint {
        val now = System.currentTimeMillis()
        val existing = resolveHint(context, playbackContext)
        val hint = LiveAudioCompatibilityHint(
            deviceProfile = playbackContext.deviceProfile,
            channelKey = playbackContext.channelKey,
            streamKey = playbackContext.streamKey,
            passthroughEnabled = passthroughEnabled,
            preferSurround = preferSurround,
            outputMode = outputMode.storageValue,
            recoveryKind = recoveryKind,
            preferredEngine = engineId?.storageValue ?: existing?.preferredEngine,
            preferredTrack = preferredTrack ?: existing?.preferredTrack,
            audioSignature = audioSignature ?: existing?.audioSignature,
            softwareAudioRequired = softwareAudioRequired,
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            successCount = (existing?.successCount ?: 0) + 1,
            failureCount = existing?.failureCount ?: 0,
            lastFailureReason = null,
        )

        val updatedHints = loadHints(context)
            .filterNot { existingHint ->
                existingHint.deviceProfile == playbackContext.deviceProfile &&
                    existingHint.channelKey == playbackContext.channelKey &&
                    existingHint.streamKey == playbackContext.streamKey
            }
            .plus(hint)
            .filterNot(::isExpired)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(MAX_HINT_COUNT)

        saveHints(context, updatedHints)
        clearTerminalFailure(context, playbackContext)
        Log.i(
            TAG,
            "Stored playback profile channel=${playbackContext.displayName} " +
                "engine=${hint.preferredEngine ?: "none"} recovery=${hint.recoveryKind.name} " +
                "track=${hint.preferredTrack?.formatKey ?: "none"} " +
                "softwareAudio=${hint.softwareAudioRequired} successes=${hint.successCount}",
        )
        sessionState[playbackContext.sessionKey] = sessionState[playbackContext.sessionKey]
            ?.copy(hint = hint, terminalFailure = null)
            ?: SessionState(hint = hint)
        return hint
    }

    fun recordFailure(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
        reason: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = resolveHint(context, playbackContext)
        val failureHint = existing?.copy(
            updatedAtEpochMs = now,
            failureCount = (existing.failureCount) + 1,
            lastFailureReason = reason.take(240),
        ) ?: return

        val updatedHints = loadHints(context)
            .filterNot { hint ->
                hint.deviceProfile == playbackContext.deviceProfile &&
                    hint.channelKey == playbackContext.channelKey &&
                    hint.streamKey == playbackContext.streamKey
            }
            .plus(failureHint)
            .filterNot(::isExpired)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(MAX_HINT_COUNT)

        saveHints(context, updatedHints)
        Log.w(
            TAG,
            "Recorded playback profile failure channel=${playbackContext.displayName} " +
                "engine=${failureHint.preferredEngine ?: "none"} failures=${failureHint.failureCount} " +
                "reason=${failureHint.lastFailureReason ?: "unknown"}",
        )
        sessionState[playbackContext.sessionKey] = SessionState(
            hint = failureHint,
            incompatiblePreferencesKey = sessionState[playbackContext.sessionKey]?.incompatiblePreferencesKey,
            terminalFailure = sessionState[playbackContext.sessionKey]?.terminalFailure,
        )
    }

    fun rememberTerminalFailure(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
        preferencesKey: String,
        selectedMime: String?,
        audioSignature: String?,
        finalRecoveryMode: String,
        finalTuneState: String,
        reason: String,
    ): LiveAudioTerminalFailureHint {
        val now = System.currentTimeMillis()
        val existing = resolveTerminalFailure(context, playbackContext, preferencesKey)
        val failure = LiveAudioTerminalFailureHint(
            deviceProfile = playbackContext.deviceProfile,
            channelKey = playbackContext.channelKey,
            streamKey = playbackContext.streamKey,
            preferencesKey = preferencesKey,
            selectedMime = selectedMime,
            audioSignature = audioSignature,
            terminalFailureKind = LiveAudioTerminalFailureKind.EXOPLAYER_AUDIO_UNRECOVERABLE.name,
            finalRecoveryMode = finalRecoveryMode,
            finalTuneState = finalTuneState,
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            failureCount = (existing?.failureCount ?: 0) + 1,
            lastFailureReason = reason.take(240),
        )
        val updatedFailures = loadTerminalFailures(context)
            .filterNot { hint ->
                hint.deviceProfile == playbackContext.deviceProfile &&
                    hint.channelKey == playbackContext.channelKey &&
                    hint.streamKey == playbackContext.streamKey &&
                    hint.preferencesKey == preferencesKey
            }
            .plus(failure)
            .filterNot(::isExpired)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(MAX_TERMINAL_FAILURE_COUNT)

        saveTerminalFailures(context, updatedFailures)
        Log.w(
            TAG,
            "Stored terminal playback failure channel=${playbackContext.displayName} " +
                "kind=${failure.terminalFailureKind} recovery=${failure.finalRecoveryMode} " +
                "mime=${failure.selectedMime ?: "unknown"} failures=${failure.failureCount}",
        )
        sessionState[playbackContext.sessionKey] = sessionState[playbackContext.sessionKey]
            ?.copy(terminalFailure = failure, incompatiblePreferencesKey = preferencesKey)
            ?: SessionState(incompatiblePreferencesKey = preferencesKey, terminalFailure = failure)
        return failure
    }

    fun resolvePreferredEngine(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
    ): LivePlayerEngineId? {
        val hint = resolveHint(context, playbackContext) ?: return null
        val preferredEngine = hint.preferredEngineId() ?: return null
        if (
            preferredEngine == LivePlayerEngineId.MPV &&
            hint.recoveryKind == LiveAudioRecoveryKind.MOBILE_REFERENCE_FIRST_PASS
        ) {
            return null
        }
        return preferredEngine
    }

    fun invalidateHint(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
    ) {
        sessionState[playbackContext.sessionKey] = sessionState[playbackContext.sessionKey]
            ?.copy(hint = null)
            ?: SessionState()
        val updatedHints = loadHints(context).filterNot { hint ->
            hint.deviceProfile == playbackContext.deviceProfile &&
                hint.channelKey == playbackContext.channelKey &&
                hint.streamKey == playbackContext.streamKey
        }
        saveHints(context, updatedHints)
        Log.i(
            TAG,
            "Invalidated playback profile channel=${playbackContext.displayName} " +
                "sessionKey=${playbackContext.sessionKey.takeLast(12)}",
        )
    }

    fun clearTerminalFailure(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
    ) {
        sessionState[playbackContext.sessionKey] = sessionState[playbackContext.sessionKey]
            ?.copy(terminalFailure = null)
            ?: SessionState()
        val updatedFailures = loadTerminalFailures(context).filterNot { hint ->
            hint.deviceProfile == playbackContext.deviceProfile &&
                hint.channelKey == playbackContext.channelKey &&
                hint.streamKey == playbackContext.streamKey
        }
        saveTerminalFailures(context, updatedFailures)
        Log.i(
            TAG,
            "Cleared terminal playback failure channel=${playbackContext.displayName} " +
                "sessionKey=${playbackContext.sessionKey.takeLast(12)}",
        )
    }

    fun markSessionIncompatible(
        playbackContext: LiveAudioPlaybackContext,
        preferencesKey: String,
    ) {
        val existing = sessionState[playbackContext.sessionKey]
        sessionState[playbackContext.sessionKey] = SessionState(
            hint = existing?.hint,
            incompatiblePreferencesKey = preferencesKey,
            terminalFailure = existing?.terminalFailure,
        )
    }

    fun clearSessionIncompatible(playbackContext: LiveAudioPlaybackContext) {
        val existing = sessionState[playbackContext.sessionKey] ?: return
        sessionState[playbackContext.sessionKey] = existing.copy(incompatiblePreferencesKey = null)
    }

    fun isSessionIncompatible(
        playbackContext: LiveAudioPlaybackContext,
        preferencesKey: String,
    ): Boolean {
        return sessionState[playbackContext.sessionKey]?.incompatiblePreferencesKey == preferencesKey
    }

    private fun loadHints(context: Context): List<LiveAudioCompatibilityHint> {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY_HINTS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<LiveAudioCompatibilityHint>>(raw)
        }.getOrDefault(emptyList())
        val pruned = parsed
            .filterNot(::isExpired)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(MAX_HINT_COUNT)
        if (pruned.size != parsed.size) {
            saveHints(context, pruned)
        }
        return pruned
    }

    private fun saveHints(context: Context, hints: List<LiveAudioCompatibilityHint>) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_HINTS, json.encodeToString(hints)).apply()
    }

    private fun isExpired(hint: LiveAudioCompatibilityHint): Boolean {
        return System.currentTimeMillis() - hint.updatedAtEpochMs > MAX_HINT_AGE_MS
    }

    private fun loadTerminalFailures(context: Context): List<LiveAudioTerminalFailureHint> {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY_TERMINAL_FAILURES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<LiveAudioTerminalFailureHint>>(raw)
        }.getOrDefault(emptyList())
        val pruned = parsed
            .filterNot(::isExpired)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(MAX_TERMINAL_FAILURE_COUNT)
        if (pruned.size != parsed.size) {
            saveTerminalFailures(context, pruned)
        }
        return pruned
    }

    private fun saveTerminalFailures(context: Context, failures: List<LiveAudioTerminalFailureHint>) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_TERMINAL_FAILURES, json.encodeToString(failures)).apply()
    }

    private fun isExpired(hint: LiveAudioTerminalFailureHint): Boolean {
        return System.currentTimeMillis() - hint.updatedAtEpochMs > MAX_TERMINAL_FAILURE_AGE_MS
    }
}
