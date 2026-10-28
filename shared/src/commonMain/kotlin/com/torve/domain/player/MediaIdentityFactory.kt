package com.torve.domain.player

import com.torve.data.addon.SourceProfile

object MediaIdentityFactory {
    fun fromSourceProfile(
        canonicalEpisodeId: String,
        runtimeMs: Long,
        profile: SourceProfile?,
        fallbackSourceKey: String? = null,
    ): MediaIdentity {
        val exactHash = profile?.movieHash?.takeIf(String::isNotBlank)
            ?: profile?.infoHash?.takeIf(String::isNotBlank)?.let { "$it:${profile.fileIndex ?: -1}" }
        val opaqueFallback = fallbackSourceKey
            ?.takeIf { profile == null && exactHash == null && it.isNotBlank() }
            ?.substringBefore('?')
            ?.substringBefore('#')
        val signature = listOf(
            exactHash?.let { "hash=$it" },
            // Signed query parameters rotate frequently and must never become cache identity.
            opaqueFallback?.let { "opaque=${stableHash(it)}" },
            profile?.releaseFamily?.let { "family=$it" },
            profile?.releaseGroup?.let { "group=$it" },
            profile?.resolutionHeight?.let { "height=$it" },
            profile?.codec?.let { "video=$it" },
            profile?.audioCodec?.let { "audio=$it" },
            profile?.audioChannels?.let { "channels=$it" },
            profile?.fps?.let { "fps=${normalizeFps(it)}" },
            profile?.edition?.let { "edition=$it" },
            profile?.provider?.let { "provider=$it" },
            "runtime=$runtimeMs",
        ).filterNotNull().joinToString("|")
        return MediaIdentity(
            canonicalEpisodeId = canonicalEpisodeId,
            runtimeMs = runtimeMs,
            releaseName = profile?.releaseName,
            releaseGroup = profile?.releaseGroup,
            releaseFamily = profile?.releaseFamily,
            resolutionHeight = profile?.resolutionHeight,
            videoCodec = profile?.codec,
            audioCodec = profile?.audioCodec,
            audioChannels = profile?.audioChannels,
            frameRate = profile?.fps,
            edition = profile?.edition,
            provider = profile?.provider,
            exactStreamHash = exactHash,
            fileIndex = profile?.fileIndex,
            fingerprint = stableHash(signature),
        )
    }

    private fun normalizeFps(value: Double): String = when {
        kotlin.math.abs(value - 23.976) < 0.02 -> "23.976"
        kotlin.math.abs(value - 29.97) < 0.02 -> "29.970"
        else -> value.toString()
    }

    /** Stable FNV-1a avoids platform crypto differences and never includes a URL. */
    internal fun stableHash(value: String): String {
        var hash = 0xcbf29ce484222325UL
        value.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3UL
        }
        return hash.toString(16).padStart(16, '0')
    }
}
