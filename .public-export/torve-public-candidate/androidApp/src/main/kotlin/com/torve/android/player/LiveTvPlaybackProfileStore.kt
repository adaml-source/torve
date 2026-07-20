package com.torve.android.player

import android.content.Context
import android.util.Log
import com.torve.domain.model.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

internal enum class LiveTvPlaybackPhase {
    ZAP,
    STEADY,
}

internal enum class LiveTvVideoSyncMode(val storageValue: String) {
    DISPLAY_RESAMPLE("display-resample"),
    DISPLAY_VDROP("display-vdrop"),
    AUDIO("audio");

    companion object {
        fun fromStorage(value: String?): LiveTvVideoSyncMode {
            return entries.firstOrNull { it.storageValue == value } ?: DISPLAY_RESAMPLE
        }
    }
}

internal enum class LiveTvDeinterlaceMode(val storageValue: String) {
    OFF("off"),
    AUTO("auto"),
    FORCE("force");

    companion object {
        fun fromStorage(value: String?): LiveTvDeinterlaceMode {
            return entries.firstOrNull { it.storageValue == value } ?: AUTO
        }
    }
}

internal data class LiveTvRuntimeObservation(
    val interlacedDetected: Boolean = false,
    val estimatedFrameRate: Float? = null,
    val displayFrameRate: Float? = null,
    val mistimedFrameCount: Int? = null,
    val droppedFrameCount: Int? = null,
    val delayedFrameCount: Int? = null,
)

@Serializable
internal data class LiveTvPlaybackProfile(
    val deviceProfile: String,
    val channelKey: String,
    val streamKey: String,
    val videoSyncMode: String,
    val deinterlaceMode: String,
    val interpolationEnabled: Boolean,
    val tscaleMode: String,
    val hardwareDecodeMode: String,
    val zapCacheSecs: Int,
    val steadyCacheSecs: Int,
    val zapReadaheadSecs: Int,
    val steadyReadaheadSecs: Int,
    val zapMaxBytesMiB: Int,
    val steadyMaxBytesMiB: Int,
    val backBufferMiB: Int,
    val streamBufferKiB: Int,
    val demuxerProbeBytes: Int,
    val demuxerAnalyzeDurationUs: Long,
    val linearizeTimestamps: Boolean,
    val forceSeekable: Boolean,
    val diskCacheEnabled: Boolean,
    val likelyTransportStream: Boolean,
    val frameRateHint: Float? = null,
    val interlacedSeen: Boolean = false,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastFailureReason: String? = null,
) {
    fun videoSyncMode(): LiveTvVideoSyncMode = LiveTvVideoSyncMode.fromStorage(videoSyncMode)
    fun deinterlaceMode(): LiveTvDeinterlaceMode = LiveTvDeinterlaceMode.fromStorage(deinterlaceMode)

    fun cacheSecs(phase: LiveTvPlaybackPhase): Int = when (phase) {
        LiveTvPlaybackPhase.ZAP -> zapCacheSecs
        LiveTvPlaybackPhase.STEADY -> steadyCacheSecs
    }

    fun readaheadSecs(phase: LiveTvPlaybackPhase): Int = when (phase) {
        LiveTvPlaybackPhase.ZAP -> zapReadaheadSecs
        LiveTvPlaybackPhase.STEADY -> steadyReadaheadSecs
    }

    fun maxBytesMiB(phase: LiveTvPlaybackPhase): Int = when (phase) {
        LiveTvPlaybackPhase.ZAP -> zapMaxBytesMiB
        LiveTvPlaybackPhase.STEADY -> steadyMaxBytesMiB
    }
}

internal object LiveTvPlaybackHeuristics {
    private val europeanBroadcastCountries = setOf(
        "de", "at", "ch", "fr", "it", "es", "pt", "nl", "be", "gb", "uk",
        "ie", "se", "no", "dk", "fi", "pl", "cz", "sk", "hu", "ro", "bg",
        "hr", "si", "rs", "gr", "tr",
    )

    fun defaultProfile(
        channel: Channel,
        playbackContext: LiveAudioPlaybackContext,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LiveTvPlaybackProfile {
        val normalizedUrl = channel.url.lowercase(Locale.ROOT)
        val likelyTransportStream = normalizedUrl.contains(".ts") ||
            normalizedUrl.contains("mpegts") ||
            normalizedUrl.contains("transport") ||
            normalizedUrl.contains("mime=video/mp2t") ||
            normalizedUrl.contains("output=ts")
        val likelyEuropeanBroadcast = channel.tvgCountry
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.let(europeanBroadcastCountries::contains)
            ?: false
        val forceSeekable = channel.catchupType != null ||
            channel.catchupDays != null ||
            channel.catchupSource != null

        return LiveTvPlaybackProfile(
            deviceProfile = playbackContext.deviceProfile,
            channelKey = playbackContext.channelKey,
            streamKey = playbackContext.streamKey,
            videoSyncMode = LiveTvVideoSyncMode.DISPLAY_RESAMPLE.storageValue,
            deinterlaceMode = if (likelyEuropeanBroadcast) {
                LiveTvDeinterlaceMode.AUTO.storageValue
            } else {
                LiveTvDeinterlaceMode.OFF.storageValue
            },
            interpolationEnabled = false,
            tscaleMode = "oversample",
            hardwareDecodeMode = "auto-safe",
            zapCacheSecs = if (likelyTransportStream) 4 else 3,
            steadyCacheSecs = if (likelyTransportStream) 24 else 18,
            zapReadaheadSecs = if (likelyTransportStream) 2 else 1,
            steadyReadaheadSecs = if (likelyTransportStream) 10 else 8,
            zapMaxBytesMiB = if (likelyTransportStream) 24 else 16,
            steadyMaxBytesMiB = if (likelyTransportStream) 96 else 64,
            backBufferMiB = if (forceSeekable) 64 else if (likelyTransportStream) 32 else 16,
            streamBufferKiB = if (likelyTransportStream) 1024 else 512,
            demuxerProbeBytes = if (likelyTransportStream) 2_097_152 else 1_048_576,
            demuxerAnalyzeDurationUs = if (likelyTransportStream) 2_500_000L else 1_500_000L,
            linearizeTimestamps = likelyTransportStream,
            forceSeekable = forceSeekable,
            diskCacheEnabled = false,
            likelyTransportStream = likelyTransportStream,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    fun mergeSuccessfulObservation(
        base: LiveTvPlaybackProfile,
        observation: LiveTvRuntimeObservation,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LiveTvPlaybackProfile {
        val interlacedSeen = base.interlacedSeen || observation.interlacedDetected
        val frameRateHint = observation.estimatedFrameRate ?: base.frameRateHint
        return base.copy(
            deinterlaceMode = when {
                interlacedSeen -> LiveTvDeinterlaceMode.FORCE.storageValue
                else -> base.deinterlaceMode
            },
            frameRateHint = frameRateHint,
            interlacedSeen = interlacedSeen,
            updatedAtEpochMs = nowEpochMs,
            successCount = base.successCount + 1,
            lastFailureReason = null,
        )
    }

    fun mergePlaybackIssue(
        base: LiveTvPlaybackProfile,
        reason: String,
        observation: LiveTvRuntimeObservation,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LiveTvPlaybackProfile {
        val normalizedReason = reason.trim().lowercase(Locale.ROOT)
        val interlacedSeen = base.interlacedSeen || observation.interlacedDetected
        val dropped = observation.droppedFrameCount ?: 0
        val delayed = observation.delayedFrameCount ?: 0
        val mistimed = observation.mistimedFrameCount ?: 0
        val bufferingIssue = normalizedReason.contains("stall") ||
            normalizedReason.contains("buffer") ||
            delayed >= 4
        val framePacingIssue = normalizedReason.contains("judder") ||
            normalizedReason.contains("stutter") ||
            normalizedReason.contains("mistimed") ||
            mistimed >= 8 ||
            dropped >= 6

        return base.copy(
            videoSyncMode = when {
                interlacedSeen -> LiveTvVideoSyncMode.DISPLAY_RESAMPLE.storageValue
                framePacingIssue && base.videoSyncMode() == LiveTvVideoSyncMode.DISPLAY_RESAMPLE ->
                    LiveTvVideoSyncMode.DISPLAY_VDROP.storageValue
                else -> base.videoSyncMode
            },
            deinterlaceMode = when {
                interlacedSeen -> LiveTvDeinterlaceMode.FORCE.storageValue
                else -> base.deinterlaceMode
            },
            steadyCacheSecs = if (bufferingIssue) (base.steadyCacheSecs + 6).coerceAtMost(36) else base.steadyCacheSecs,
            steadyReadaheadSecs = if (bufferingIssue) (base.steadyReadaheadSecs + 4).coerceAtMost(18) else base.steadyReadaheadSecs,
            steadyMaxBytesMiB = if (bufferingIssue) (base.steadyMaxBytesMiB + 32).coerceAtMost(160) else base.steadyMaxBytesMiB,
            backBufferMiB = if (bufferingIssue) (base.backBufferMiB + 16).coerceAtMost(128) else base.backBufferMiB,
            frameRateHint = observation.estimatedFrameRate ?: base.frameRateHint,
            interlacedSeen = interlacedSeen,
            updatedAtEpochMs = nowEpochMs,
            failureCount = base.failureCount + 1,
            lastFailureReason = reason.take(240),
        )
    }
}

internal object LiveTvPlaybackProfileStore {
    private const val prefName = "torve_live_tv_playback_profiles"
    private const val prefKeyProfiles = "profiles_v1"
    private const val maxProfileCount = 64
    private const val maxProfileAgeMs = 21L * 24L * 60L * 60L * 1000L
    private const val tag = "LiveTvProfile"

    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(
        context: Context,
        channel: Channel,
        playbackContext: LiveAudioPlaybackContext,
    ): LiveTvPlaybackProfile {
        val persisted = loadProfiles(context).firstOrNull { profile ->
            profile.deviceProfile == playbackContext.deviceProfile &&
                profile.channelKey == playbackContext.channelKey &&
                profile.streamKey == playbackContext.streamKey &&
                !isExpired(profile)
        }
        val resolved = persisted ?: LiveTvPlaybackHeuristics.defaultProfile(channel, playbackContext)
        Log.d(
            tag,
            "Resolved live TV profile channel=${playbackContext.displayName} " +
                "sync=${resolved.videoSyncMode} deinterlace=${resolved.deinterlaceMode} " +
                "cache=${resolved.steadyCacheSecs}s back=${resolved.backBufferMiB}MiB " +
                "ts=${resolved.likelyTransportStream}",
        )
        return resolved
    }

    fun rememberSuccessfulPlayback(
        context: Context,
        channel: Channel,
        playbackContext: LiveAudioPlaybackContext,
        profile: LiveTvPlaybackProfile,
        observation: LiveTvRuntimeObservation,
    ): LiveTvPlaybackProfile {
        val merged = LiveTvPlaybackHeuristics.mergeSuccessfulObservation(
            base = currentProfileOrDefault(context, channel, playbackContext, profile),
            observation = observation,
        )
        saveProfile(context, merged)
        Log.i(
            tag,
            "Stored live TV profile success channel=${playbackContext.displayName} " +
                "sync=${merged.videoSyncMode} deinterlace=${merged.deinterlaceMode} " +
                "interlaced=${merged.interlacedSeen} fps=${merged.frameRateHint ?: -1f}",
        )
        return merged
    }

    fun recordPlaybackIssue(
        context: Context,
        channel: Channel,
        playbackContext: LiveAudioPlaybackContext,
        profile: LiveTvPlaybackProfile,
        reason: String,
        observation: LiveTvRuntimeObservation,
    ): LiveTvPlaybackProfile {
        val merged = LiveTvPlaybackHeuristics.mergePlaybackIssue(
            base = currentProfileOrDefault(context, channel, playbackContext, profile),
            reason = reason,
            observation = observation,
        )
        saveProfile(context, merged)
        Log.w(
            tag,
            "Stored live TV profile issue channel=${playbackContext.displayName} " +
                "reason=${merged.lastFailureReason ?: "unknown"} sync=${merged.videoSyncMode} " +
                "cache=${merged.steadyCacheSecs}s back=${merged.backBufferMiB}MiB failures=${merged.failureCount}",
        )
        return merged
    }

    private fun currentProfileOrDefault(
        context: Context,
        channel: Channel,
        playbackContext: LiveAudioPlaybackContext,
        profile: LiveTvPlaybackProfile,
    ): LiveTvPlaybackProfile {
        return loadProfiles(context).firstOrNull {
            it.deviceProfile == playbackContext.deviceProfile &&
                it.channelKey == playbackContext.channelKey &&
                it.streamKey == playbackContext.streamKey
        } ?: profile.copy(
            deviceProfile = playbackContext.deviceProfile,
            channelKey = playbackContext.channelKey,
            streamKey = playbackContext.streamKey,
        )
    }

    private fun saveProfile(
        context: Context,
        profile: LiveTvPlaybackProfile,
    ) {
        val updated = loadProfiles(context)
            .filterNot {
                it.deviceProfile == profile.deviceProfile &&
                    it.channelKey == profile.channelKey &&
                    it.streamKey == profile.streamKey
            }
            .plus(profile)
            .filterNot(::isExpired)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(maxProfileCount)
        val prefs = context.applicationContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putString(prefKeyProfiles, json.encodeToString(updated)).apply()
    }

    private fun loadProfiles(context: Context): List<LiveTvPlaybackProfile> {
        val prefs = context.applicationContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val raw = prefs.getString(prefKeyProfiles, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<LiveTvPlaybackProfile>>(raw)
        }.getOrDefault(emptyList())
        val pruned = parsed
            .filterNot(::isExpired)
            .sortedByDescending { it.updatedAtEpochMs }
            .take(maxProfileCount)
        if (pruned.size != parsed.size) {
            prefs.edit().putString(prefKeyProfiles, json.encodeToString(pruned)).apply()
        }
        return pruned
    }

    private fun isExpired(profile: LiveTvPlaybackProfile): Boolean {
        return System.currentTimeMillis() - profile.updatedAtEpochMs > maxProfileAgeMs
    }
}
