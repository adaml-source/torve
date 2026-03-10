package com.torve.android.player

import android.content.Context
import android.os.Build
import com.torve.domain.model.Channel
import com.torve.domain.player.LiveAudioOutputMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

internal enum class LiveAudioRecoveryKind {
    COMPATIBLE_MODE,
    COMPATIBLE_TRACK,
    STEREO_PCM,
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
    val preferredTrack: LiveAudioTrackHint? = null,
    val audioSignature: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val successCount: Int = 1,
) {
    fun liveAudioOutputMode(): LiveAudioOutputMode = LiveAudioOutputMode.fromStorage(outputMode)
}

internal object LiveAudioCompatibilityStore {
    private const val PREF_NAME = "torve_live_audio_compatibility"
    private const val PREF_KEY_HINTS = "live_audio_hints_v1"
    private const val MAX_HINT_COUNT = 48
    private const val MAX_HINT_AGE_MS = 21L * 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private data class SessionState(
        val hint: LiveAudioCompatibilityHint? = null,
        val incompatiblePreferencesKey: String? = null,
    )

    private val sessionState = mutableMapOf<String, SessionState>()

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

        sessionState[playbackContext.sessionKey] = sessionState[playbackContext.sessionKey]
            ?.copy(hint = match)
            ?: SessionState(hint = match)
        return match
    }

    fun rememberSuccessfulRecovery(
        context: Context,
        playbackContext: LiveAudioPlaybackContext,
        passthroughEnabled: Boolean,
        preferSurround: Boolean,
        outputMode: LiveAudioOutputMode,
        recoveryKind: LiveAudioRecoveryKind,
        preferredTrack: LiveAudioTrackHint?,
        audioSignature: String?,
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
            preferredTrack = preferredTrack,
            audioSignature = audioSignature,
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
            successCount = (existing?.successCount ?: 0) + 1,
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
        sessionState[playbackContext.sessionKey] = SessionState(hint = hint)
        return hint
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
    }

    fun markSessionIncompatible(
        playbackContext: LiveAudioPlaybackContext,
        preferencesKey: String,
    ) {
        val existing = sessionState[playbackContext.sessionKey]
        sessionState[playbackContext.sessionKey] = SessionState(
            hint = existing?.hint,
            incompatiblePreferencesKey = preferencesKey,
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
}
