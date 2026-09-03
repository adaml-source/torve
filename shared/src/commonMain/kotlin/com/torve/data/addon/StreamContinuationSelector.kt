package com.torve.data.addon

import kotlinx.datetime.Clock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How closely an autoplay candidate preserves the source that is already playing. */
enum class ContinuationFallbackTier(val priority: Int) {
    SAME_RELEASE_FAMILY(0),
    SAME_FORMAT(1),
    SAME_OR_HIGHER_RESOLUTION(2),
    NEAREST_LOWER_RESOLUTION(3),
    ANY_PLAYABLE(4),
}

enum class ContinuationPlaybackOutcome {
    STARTED,
    RESOLVE_FAILURE,
    STARTUP_TIMEOUT,
    HTTP_FAILURE,
    PLAYBACK_ERROR,
    EARLY_BUFFERING_FAILURE,
}

enum class ContinuationSelectionOrigin {
    MANUAL,
    AUTOMATIC,
    RETRY,
}

/**
 * Normalized, metadata-tolerant description of a selected source. Every field is optional on
 * purpose: addon results are heterogeneous and continuity must not depend on one magic field.
 */
data class SourceProfile(
    val sourceKey: String,
    val rawProvider: String?,
    val provider: String?,
    val rawSourceName: String?,
    val source: String?,
    val releaseName: String?,
    val filename: String?,
    val seriesStem: String?,
    val releaseFamily: String?,
    val releaseGroup: String?,
    val resolutionHeight: Int?,
    val releaseType: String?,
    val codec: String?,
    val dynamicRange: String?,
    val audioCodec: String?,
    val audioChannels: String?,
    val languages: Set<String>,
    val container: String?,
    val sizeBytes: Long?,
    val bitrateBitsPerSecond: Long?,
    val runtimeMs: Long?,
    val seasonEpisodePattern: String?,
    val host: String?,
    val transport: String?,
    val cached: Boolean?,
    val generalQualityScore: Int,
    val parsedTitle: String?,
    val year: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val normalizedCompleteRelease: String?,
    val repack: Boolean,
    val proper: Boolean,
    val edition: String?,
    val fps: Double?,
    val infoHash: String?,
    val fileIndex: Int?,
    val movieHash: String? = null,
    val movieHashAvailable: Boolean = false,
) {
    fun debugSummary(): String = buildString {
        append("family=").append(releaseFamily ?: "unknown")
        append(" group=").append(releaseGroup ?: "unknown")
        append(" resolution=").append(resolutionHeight?.let { "${it}p" } ?: "unknown")
        append(" type=").append(releaseType ?: "unknown")
        append(" codec=").append(codec ?: "unknown")
        append(" range=").append(dynamicRange ?: "unknown")
        append(" audio=").append(audioCodec ?: "unknown")
        append(" channels=").append(audioChannels ?: "unknown")
        append(" sizeBytes=").append(sizeBytes ?: "unknown")
        append(" bitrate=").append(bitrateBitsPerSecond ?: "unknown")
        append(" runtimeMs=").append(runtimeMs ?: "unknown")
        append(" provider=").append(provider ?: "unknown")
        append(" transport=").append(transport ?: "unknown")
        append(" cached=").append(cached ?: "unknown")
    }

    companion object {
        fun from(
            stream: ParsedStream,
            resolvedUrl: String? = null,
            resolvedFileName: String? = null,
            resolvedFileSize: Long? = null,
            durationMs: Long? = null,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null,
        ): SourceProfile {
            val releaseName = bestReleaseName(resolvedFileName, stream.title)
            val normalized = normalizeReleaseIdentity(releaseName)
            val mergedText = listOfNotNull(releaseName, stream.quality, stream.codec, stream.hdr, stream.audioCodec)
                .joinToString(" ")
            val sizeBytes = resolvedFileSize?.takeIf { it > 0L } ?: parseStreamSizeBytes(stream.size)
            val duration = durationMs?.takeIf { it > 0L }
            val bitrate = if (sizeBytes != null && duration != null) {
                ((sizeBytes.toDouble() * 8_000.0) / duration.toDouble()).toLong()
            } else {
                null
            }
            val host = StreamRuntimeTelemetry.keyForUrl(resolvedUrl ?: stream.directUrl)
                ?: StreamRuntimeTelemetry.keyForStream(stream).takeUnless { it.startsWith("addon:") }
            val resolution = parseResolutionHeight(stream.quality, mergedText)
            val releaseType = parseReleaseType(mergedText)
            val codec = normalizeVideoCodec(stream.codec, mergedText)
            val dynamicRange = normalizeDynamicRange(stream.hdr, mergedText)
            val audioCodec = normalizeAudioCodec(stream.audioCodec, mergedText)
            val audioChannels = parseAudioChannels(mergedText)
            val family = buildReleaseFamily(
                seriesStem = normalized.seriesStem,
                releaseGroup = normalized.releaseGroup,
                resolutionHeight = resolution,
                releaseType = releaseType,
                codec = codec,
                dynamicRange = dynamicRange,
                audioCodec = audioCodec,
            )
            return SourceProfile(
                sourceKey = continuationSourceKey(stream),
                rawProvider = stream.addonName.trim().takeIf { it.isNotBlank() },
                provider = stream.addonName.trim().lowercase().takeIf { it.isNotBlank() },
                rawSourceName = stream.source?.trim()?.takeIf { it.isNotBlank() },
                source = stream.source?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
                releaseName = releaseName,
                filename = resolvedFileName?.trim()?.takeIf { it.isNotBlank() },
                seriesStem = normalized.seriesStem,
                releaseFamily = family,
                releaseGroup = normalized.releaseGroup,
                resolutionHeight = resolution,
                releaseType = releaseType,
                codec = codec,
                dynamicRange = dynamicRange,
                audioCodec = audioCodec,
                audioChannels = audioChannels,
                languages = stream.languages.mapNotNull(::normalizeLanguageCode).toSet(),
                container = parseContainer(resolvedFileName ?: releaseName, resolvedUrl ?: stream.directUrl),
                sizeBytes = sizeBytes,
                bitrateBitsPerSecond = bitrate,
                runtimeMs = duration,
                seasonEpisodePattern = normalized.seasonEpisodePattern,
                host = host,
                transport = when {
                    stream.isUsenetStream() -> "usenet"
                    stream.infoHash != null || stream.magnetUrl != null -> "torrent-debrid"
                    stream.isAddonHostedUrl() -> "addon-direct"
                    stream.directUrl != null -> "direct"
                    else -> null
                },
                cached = when {
                    stream.isCached -> true
                    stream.infoHash != null || stream.magnetUrl != null -> false
                    else -> null
                },
                generalQualityScore = stream.score,
                parsedTitle = normalized.seriesStem,
                year = parseReleaseYear(releaseName),
                seasonNumber = seasonNumber ?: parseSeasonNumber(releaseName),
                episodeNumber = episodeNumber ?: parseEpisodeNumber(releaseName),
                normalizedCompleteRelease = normalizeCompleteRelease(releaseName),
                repack = releaseName?.contains("REPACK", ignoreCase = true) == true,
                proper = releaseName?.contains("PROPER", ignoreCase = true) == true,
                edition = parseEdition(releaseName),
                fps = parseReleaseFps(releaseName),
                infoHash = stream.infoHash?.lowercase(),
                fileIndex = stream.fileIdx,
            )
        }
    }
}

data class NormalizedReleaseIdentity(
    val seriesStem: String?,
    val releaseGroup: String?,
    val seasonEpisodePattern: String?,
)

data class ContinuationScoreFactor(
    val label: String,
    val points: Int,
)

data class RankedContinuationSource(
    val stream: ParsedStream,
    val profile: SourceProfile,
    val tier: ContinuationFallbackTier,
    val score: Int,
    val factors: List<ContinuationScoreFactor>,
) {
    fun debugSummary(): String {
        val factorText = factors.joinToString(", ") { factor ->
            "${factor.label} ${if (factor.points >= 0) "+" else ""}${factor.points}"
        }
        val safeCandidate = profile.releaseName?.take(120)
            ?: listOfNotNull(profile.provider, profile.resolutionHeight?.let { "${it}p" }, profile.releaseType)
                .joinToString("/")
                .ifBlank { "metadata-unavailable" }
        return "candidate=$safeCandidate tier=$tier score=$score: $factorText"
    }
}

/** Pure ranking layer used only for continuation/autoplay decisions. */
class StreamContinuationSelector(
    private val reliabilityAdjustment: (ParsedStream) -> Int = { stream ->
        val hostAdjustment = StreamRuntimeTelemetry.reliabilityAdjustment(
            StreamRuntimeTelemetry.keyForStream(stream),
        )
        val sessionAdjustment = SourceContinuationSessionStore.session.reliabilityAdjustment(stream)
        (hostAdjustment + sessionAdjustment).coerceIn(-32, 24)
    },
) {
    fun rankSourcesForContinuation(
        candidates: List<ParsedStream>,
        reference: SourceProfile,
        candidateDurationMs: Long? = null,
    ): List<RankedContinuationSource> {
        return candidates.map { candidate ->
            calculateSourceSimilarity(
                reference = reference,
                candidate = SourceProfile.from(candidate, durationMs = candidateDurationMs),
                stream = candidate,
            )
        }.sortedWith(
            compareBy<RankedContinuationSource> { it.tier.priority }
                .thenByDescending { it.score }
                .thenByDescending { it.profile.sizeBytes ?: -1L }
                .thenBy { it.profile.sourceKey },
        )
    }

    fun calculateSourceSimilarity(
        reference: SourceProfile,
        candidate: SourceProfile,
        stream: ParsedStream,
    ): RankedContinuationSource {
        val factors = mutableListOf<ContinuationScoreFactor>()
        fun factor(label: String, points: Int) {
            if (points != 0) factors += ContinuationScoreFactor(label, points)
        }

        val sameStem = knownEqual(reference.seriesStem, candidate.seriesStem)
        val sameGroup = knownEqual(reference.releaseGroup, candidate.releaseGroup)
        val exactFamily = knownEqual(reference.releaseFamily, candidate.releaseFamily)
        val nearFamily = sameStem && sameGroup && reference.releaseGroup != null
        when {
            exactFamily -> factor("same release family", 38)
            nearFamily -> factor("near release family", 31)
            sameGroup -> factor("same release group", 20)
            sameStem -> factor("same series naming stem", 7)
        }

        val sameResolution = knownEqual(reference.resolutionHeight, candidate.resolutionHeight)
        when {
            sameResolution -> factor("same ${reference.resolutionHeight}p", 20)
            isResolutionDowngrade(reference, candidate) -> {
                val difference = reference.resolutionHeight!! - candidate.resolutionHeight!!
                factor("resolution downgrade", if (difference <= 360) -18 else -30)
            }
            reference.resolutionHeight != null && candidate.resolutionHeight != null ->
                factor("higher resolution", 3)
        }

        compareCharacteristic(reference.releaseType, candidate.releaseType)?.let { same ->
            factor(if (same) "same release type" else "different release type", if (same) 12 else -8)
        }
        compareCharacteristic(reference.codec, candidate.codec)?.let { same ->
            factor(if (same) "same video codec" else "different video codec", if (same) 8 else -5)
        }
        compareCharacteristic(reference.dynamicRange, candidate.dynamicRange)?.let { same ->
            factor(
                if (same) "same dynamic range" else "different dynamic range",
                if (same) 8 else if (reference.dynamicRange != "sdr") -18 else -3,
            )
        }

        val sizeRatio = proportionalSimilarity(reference.sizeBytes, candidate.sizeBytes)
        if (sizeRatio != null) {
            when {
                sizeRatio >= 0.85 -> factor("size similarity ${ratioLabel(sizeRatio)}", 18)
                sizeRatio >= 0.70 -> factor("size similarity ${ratioLabel(sizeRatio)}", 13)
                sizeRatio >= 0.50 -> factor("moderate size difference ${ratioLabel(sizeRatio)}", 3)
                sizeRatio >= 0.35 -> factor("substantially smaller/larger ${ratioLabel(sizeRatio)}", -12)
                else -> factor("severe size degradation ${ratioLabel(sizeRatio)}", -28)
            }
        }
        val bitrateRatio = proportionalSimilarity(reference.bitrateBitsPerSecond, candidate.bitrateBitsPerSecond)
        if (bitrateRatio != null) {
            when {
                bitrateRatio >= 0.80 -> factor("bitrate similarity ${ratioLabel(bitrateRatio)}", 8)
                bitrateRatio >= 0.60 -> factor("moderate bitrate difference ${ratioLabel(bitrateRatio)}", 3)
                bitrateRatio < 0.35 -> factor("severe bitrate degradation ${ratioLabel(bitrateRatio)}", -12)
            }
        }

        compareCharacteristic(reference.audioCodec, candidate.audioCodec)?.let { same ->
            factor(if (same) "same audio codec" else "different audio codec", if (same) 5 else -3)
        }
        compareCharacteristic(reference.audioChannels, candidate.audioChannels)?.let { same ->
            factor(if (same) "same audio channels" else "different audio channels", if (same) 3 else -2)
        }
        if (reference.languages.isNotEmpty() && candidate.languages.isNotEmpty()) {
            factor(
                if (reference.languages.intersect(candidate.languages).isNotEmpty()) "same language" else "language mismatch",
                if (reference.languages.intersect(candidate.languages).isNotEmpty()) 4 else -7,
            )
        }
        if (knownEqual(reference.provider, candidate.provider)) factor("same addon/provider", 5)
        if (knownEqual(reference.source, candidate.source)) factor("same source path", 3)
        if (knownEqual(reference.host, candidate.host)) factor("same host", 4)
        if (knownEqual(reference.transport, candidate.transport)) factor("same transport", 3)
        if (candidate.cached == true) factor("cached", 3)
        if (stream.recentSuccessCount > 0) {
            factor("historical successful resolves", stream.recentSuccessCount.coerceAtMost(4) * 2)
        }
        val reliability = reliabilityAdjustment(stream)
        factor(if (reliability >= 0) "recent reliability" else "recent failures", reliability)
        factor("general quality", (candidate.generalQualityScore.coerceIn(0, 100) * 0.15).roundToInt())

        val sizeComparable = (sizeRatio ?: bitrateRatio)?.let { it >= 0.65 } ?: true
        val sameFormat = characteristicsCompatible(reference.releaseType, candidate.releaseType) &&
            characteristicsCompatible(reference.codec, candidate.codec) &&
            characteristicsCompatible(reference.dynamicRange, candidate.dynamicRange)
        val tier = when {
            (exactFamily || nearFamily) && sameResolution && sameFormat && sizeComparable ->
                ContinuationFallbackTier.SAME_RELEASE_FAMILY
            sameResolution && sameFormat && sizeComparable ->
                ContinuationFallbackTier.SAME_FORMAT
            sameResolution || isResolutionUpgrade(reference, candidate) ->
                ContinuationFallbackTier.SAME_OR_HIGHER_RESOLUTION
            isResolutionDowngrade(reference, candidate) ->
                ContinuationFallbackTier.NEAREST_LOWER_RESOLUTION
            else -> ContinuationFallbackTier.ANY_PLAYABLE
        }

        return RankedContinuationSource(
            stream = stream,
            profile = candidate,
            tier = tier,
            score = factors.sumOf { it.points },
            factors = factors,
        )
    }
}

/**
 * Small retry controller. A candidate is returned at most once, so playback errors cannot loop
 * back to a dead URL. It is intentionally independent of Android/Compose for deterministic tests.
 */
class ContinuationRetryPlan(rankedCandidates: List<ParsedStream>) {
    private val candidates = rankedCandidates.distinctBy(::continuationSourceKey)
    private val attempted = linkedSetOf<String>()

    fun markAttempted(stream: ParsedStream) {
        attempted += continuationSourceKey(stream)
    }

    fun markFailed(stream: ParsedStream) {
        markAttempted(stream)
    }

    fun nextCandidate(): ParsedStream? {
        val candidate = candidates.firstOrNull { continuationSourceKey(it) !in attempted } ?: return null
        markAttempted(candidate)
        return candidate
    }

    fun remainingCount(): Int = candidates.count { continuationSourceKey(it) !in attempted }
}

/**
 * Process-session continuity state. A staged source becomes the baseline only after the player
 * reports a real first frame; returning a resolver URL is deliberately not treated as success.
 */
class SourceContinuationSession {
    private data class PendingSelection(
        val stream: ParsedStream,
        val playbackUrls: Set<String>,
        val resolvedFileName: String?,
        val resolvedFileSize: Long?,
        val origin: ContinuationSelectionOrigin,
        val stagedAtMs: Long,
    )

    private data class OutcomeStats(var successes: Int = 0, var failures: Int = 0)

    private var pending: PendingSelection? = null
    private var active: SourceProfile? = null
    private var activeStream: ParsedStream? = null
    private var activePlaybackUrls: Set<String> = emptySet()
    private val sourceOutcomes = mutableMapOf<String, OutcomeStats>()
    private val providerOutcomes = mutableMapOf<String, OutcomeStats>()

    fun stageResolvedSource(
        stream: ParsedStream,
        playbackUrl: String,
        alternatePlaybackUrls: List<String> = emptyList(),
        resolvedFileName: String? = null,
        resolvedFileSize: Long? = null,
        origin: ContinuationSelectionOrigin,
    ) {
        if (playbackUrl.isBlank()) return
        pending = PendingSelection(
            stream = stream,
            playbackUrls = (listOf(playbackUrl) + alternatePlaybackUrls)
                .filter(String::isNotBlank)
                .mapTo(linkedSetOf(), ::canonicalPlaybackUrl),
            resolvedFileName = resolvedFileName,
            resolvedFileSize = resolvedFileSize,
            origin = origin,
            stagedAtMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    fun recordPlaybackStarted(
        playbackUrl: String,
        durationMs: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): SourceProfile? {
        val selection = pending?.takeIf { staged ->
            Clock.System.now().toEpochMilliseconds() - staged.stagedAtMs <= PENDING_TTL_MS &&
                canonicalPlaybackUrl(playbackUrl) in staged.playbackUrls
        } ?: return active
        val profile = SourceProfile.from(
            stream = selection.stream,
            resolvedUrl = playbackUrl,
            resolvedFileName = selection.resolvedFileName,
            resolvedFileSize = selection.resolvedFileSize,
            durationMs = durationMs,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        active = profile
        activeStream = selection.stream
        activePlaybackUrls = selection.playbackUrls
        pending = null
        recordOutcome(selection.stream, ContinuationPlaybackOutcome.STARTED)
        return profile
    }

    fun recordPlaybackFailure(playbackUrl: String, outcome: ContinuationPlaybackOutcome): ParsedStream? {
        val normalizedUrl = canonicalPlaybackUrl(playbackUrl)
        val pendingSelection = pending?.takeIf { normalizedUrl in it.playbackUrls }
        if (pendingSelection != null) {
            recordOutcome(pendingSelection.stream, outcome)
            pending = null
            return pendingSelection.stream
        }
        val playingStream = activeStream?.takeIf { normalizedUrl in activePlaybackUrls } ?: return null
        recordOutcome(playingStream, outcome)
        return playingStream
    }

    fun recordOutcome(stream: ParsedStream, outcome: ContinuationPlaybackOutcome) {
        val sourceStats = sourceOutcomes.getOrPut(continuationSourceKey(stream)) { OutcomeStats() }
        val providerKey = stream.addonName.trim().lowercase().ifBlank { "unknown" }
        val providerStats = providerOutcomes.getOrPut(providerKey) { OutcomeStats() }
        if (outcome == ContinuationPlaybackOutcome.STARTED) {
            sourceStats.successes += 1
            providerStats.successes += 1
        } else {
            sourceStats.failures += 1
            providerStats.failures += 1
        }
    }

    fun reliabilityAdjustment(stream: ParsedStream): Int {
        val source = sourceOutcomes[continuationSourceKey(stream)]
        val provider = providerOutcomes[stream.addonName.trim().lowercase()]
        val sourceScore = ((source?.successes ?: 0) * 6) - ((source?.failures ?: 0) * 12)
        val providerScore = ((provider?.successes ?: 0) * 2) - ((provider?.failures ?: 0) * 4)
        return (sourceScore + providerScore).coerceIn(-28, 18)
    }

    fun currentProfile(): SourceProfile? = active

    fun currentStream(): ParsedStream? = activeStream

    fun recordMovieHash(movieHash: String, fileSize: Long): SourceProfile? {
        val current = active ?: return null
        if (movieHash.isBlank() || fileSize <= 0L) return current
        return current.copy(
            movieHash = movieHash.lowercase(),
            movieHashAvailable = true,
            sizeBytes = current.sizeBytes ?: fileSize,
        ).also { active = it }
    }

    fun clear() {
        pending = null
        active = null
        activeStream = null
        activePlaybackUrls = emptySet()
        sourceOutcomes.clear()
        providerOutcomes.clear()
    }

    private companion object {
        const val PENDING_TTL_MS = 10 * 60_000L
    }
}

object SourceContinuationSessionStore {
    val session = SourceContinuationSession()
}

/** Canonical release identity shared by source continuity and subtitle matching. */
typealias MediaReleaseFingerprint = SourceProfile

fun normalizeReleaseIdentity(value: String?): NormalizedReleaseIdentity {
    val release = value
        ?.lineSequence()
        ?.map(String::trim)
        ?.firstOrNull { it.isNotBlank() }
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return NormalizedReleaseIdentity(null, null, null)
    val withoutExtension = release.replace(Regex("(?i)\\.(mkv|mp4|avi|webm|m4v|ts)$"), "")
    val releaseGroup = withoutExtension
        .takeIf { it.contains('-') }
        ?.substringAfterLast('-')
        ?.takeIf { suffix -> suffix.length in 2..32 && suffix.any(Char::isLetter) }
        ?.replace(Regex("[^a-zA-Z0-9]+"), "")
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    val episodeMatch = EPISODE_TOKEN.find(withoutExtension)
    val pattern = episodeMatch?.value?.let { token ->
        when {
            token.contains('x', ignoreCase = true) -> "season-x-episode"
            token.startsWith("ep", ignoreCase = true) || token.startsWith("e", ignoreCase = true) -> "episode-token"
            else -> "season-episode"
        }
    }
    val stemInput = episodeMatch?.let { withoutExtension.substring(0, it.range.first) }
        ?: withoutExtension.substringBeforeTechnicalToken()
    val seriesStem = stemInput
        .replace(Regex("[^a-zA-Z0-9]+"), " ")
        .trim()
        .lowercase()
        .takeIf { it.isNotBlank() }
    return NormalizedReleaseIdentity(seriesStem, releaseGroup, pattern)
}

fun continuationSourceKey(stream: ParsedStream): String = stream.accelerationMemoryId
    ?: stream.accelerationSourceKey
    ?: stream.infoHash?.let { hash -> "hash:${hash.lowercase()}:${stream.fileIdx ?: -1}" }
    ?: stream.magnetUrl
    ?: stream.directUrl?.let(::canonicalPlaybackUrl)
    ?: listOf(
        stream.addonName.trim().lowercase(),
        normalizeReleaseIdentity(stream.title).seriesStem.orEmpty(),
        normalizeReleaseIdentity(stream.title).releaseGroup.orEmpty(),
        stream.quality.trim().lowercase(),
        stream.codec.orEmpty().trim().lowercase(),
        stream.size.orEmpty().trim().lowercase(),
    ).joinToString("|")

private val EPISODE_TOKEN = Regex(
    "(?i)(?:^|[^a-z0-9])(?:S\\d{1,2}[ ._-]*E\\d{1,3}|\\d{1,2}x\\d{1,3}|EP(?:ISODE)?[ ._-]*\\d{1,3}|E\\d{1,3})(?:$|[^a-z0-9])",
)

private val TECHNICAL_TOKEN = Regex(
    "(?i)(?:^|[ ._-])(2160p|1080p|720p|480p|4k|uhd|web[ ._-]?dl|webrip|bluray|bdrip|hdtv|remux|x26[45]|h[ ._-]?26[45]|hevc|avc|av1)(?:$|[ ._-])",
)

private fun String.substringBeforeTechnicalToken(): String {
    val match = TECHNICAL_TOKEN.find(this) ?: return this
    return substring(0, match.range.first)
}

private fun bestReleaseName(resolvedFileName: String?, title: String): String? {
    return resolvedFileName?.trim()?.takeIf { it.isNotBlank() }
        ?: title.lineSequence().map(String::trim).firstOrNull { line ->
            line.isNotBlank() && !line.startsWith("💾") && !line.startsWith("👤") && !line.startsWith("⚙")
        }
}

private fun buildReleaseFamily(
    seriesStem: String?,
    releaseGroup: String?,
    resolutionHeight: Int?,
    releaseType: String?,
    codec: String?,
    dynamicRange: String?,
    audioCodec: String?,
): String? {
    val identity = listOfNotNull(
        seriesStem?.let { "show:$it" },
        releaseGroup?.let { "group:$it" },
        resolutionHeight?.let { "resolution:$it" },
        releaseType?.let { "type:$it" },
        codec?.let { "codec:$it" },
        dynamicRange?.let { "range:$it" },
        audioCodec?.let { "audio:$it" },
    )
    return identity.takeIf { it.isNotEmpty() }?.joinToString("|")
}

private fun parseResolutionHeight(quality: String, text: String): Int? {
    val combined = "$quality $text".uppercase()
    return when {
        combined.contains("2160") || Regex("\\b4K\\b").containsMatchIn(combined) || combined.contains("UHD") -> 2160
        combined.contains("1080") -> 1080
        combined.contains("720") -> 720
        combined.contains("576") -> 576
        combined.contains("480") -> 480
        else -> null
    }
}

private fun parseReleaseType(text: String): String? {
    val value = text.uppercase()
    return when {
        value.contains("REMUX") -> "remux"
        Regex("WEB[ ._-]?DL").containsMatchIn(value) -> "web-dl"
        Regex("WEB[ ._-]?RIP").containsMatchIn(value) -> "webrip"
        value.contains("BLURAY") || value.contains("BLU-RAY") || value.contains("BDRIP") -> "bluray"
        value.contains("HDTV") -> "hdtv"
        value.contains("DVDRIP") || value.contains("DVD-RIP") -> "dvdrip"
        else -> null
    }
}

private fun normalizeVideoCodec(codec: String?, text: String): String? {
    val value = listOfNotNull(codec, text).joinToString(" ").uppercase()
    return when {
        value.contains("AV1") -> "av1"
        value.contains("H.265") || value.contains("H265") || value.contains("X265") || value.contains("HEVC") -> "hevc"
        value.contains("H.264") || value.contains("H264") || value.contains("X264") || value.contains("AVC") -> "h264"
        value.contains("VP9") -> "vp9"
        else -> null
    }
}

private fun normalizeDynamicRange(hdr: String?, text: String): String? {
    val value = listOfNotNull(hdr, text).joinToString(" ").uppercase()
    return when {
        value.contains("DOLBY VISION") || Regex("(?:^|[ ._-])DV(?:$|[ ._-])").containsMatchIn(value) -> "dolby-vision"
        value.contains("HDR10+") || value.contains("HDR10PLUS") -> "hdr10+"
        value.contains("HDR10") -> "hdr10"
        value.contains("HDR") -> "hdr"
        value.contains("SDR") -> "sdr"
        else -> null
    }
}

private fun normalizeAudioCodec(audioCodec: String?, text: String): String? {
    val value = listOfNotNull(audioCodec, text).joinToString(" ").uppercase()
    return when {
        value.contains("TRUEHD") -> if (value.contains("ATMOS")) "truehd-atmos" else "truehd"
        value.contains("DTS-HD") || value.contains("DTSHD") -> "dts-hd"
        value.contains("EAC3") || value.contains("E-AC-3") || value.contains("DDP") ->
            if (value.contains("ATMOS")) "ddp-atmos" else "ddp"
        Regex("(?:^|[ ._-])AC3(?:$|[ ._-])").containsMatchIn(value) ||
            Regex("(?:^|[ ._-])DD(?:$|[ ._-])").containsMatchIn(value) -> "ac3"
        Regex("(?:^|[ ._-])DTS(?:$|[ ._-])").containsMatchIn(value) -> "dts"
        value.contains("AAC") -> "aac"
        value.contains("OPUS") -> "opus"
        else -> null
    }
}

private fun parseAudioChannels(text: String): String? {
    return Regex("(?i)(?:^|[^0-9])(7\\.1|5\\.1|2\\.1|2\\.0|1\\.0)(?:[^0-9]|$)")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
}

private fun parseContainer(name: String?, url: String?): String? {
    val value = listOfNotNull(name, url?.substringBefore('?')).joinToString(" ").lowercase()
    return listOf("mkv", "mp4", "webm", "avi", "m4v", "ts")
        .firstOrNull { extension -> Regex("\\.$extension(?:$|[ /])").containsMatchIn(value) }
}

private fun canonicalPlaybackUrl(url: String): String = url.trim().substringBefore('#')

private fun knownEqual(left: Any?, right: Any?): Boolean = left != null && right != null && left == right

private fun compareCharacteristic(left: String?, right: String?): Boolean? {
    if (left == null || right == null) return null
    return left == right
}

private fun characteristicsCompatible(left: String?, right: String?): Boolean =
    left == null || right == null || left == right

private fun proportionalSimilarity(left: Long?, right: Long?): Double? {
    if (left == null || right == null || left <= 0L || right <= 0L) return null
    return min(left.toDouble(), right.toDouble()) / max(left.toDouble(), right.toDouble())
}

private fun ratioLabel(value: Double): String = "${(value * 100).roundToInt()}%"

private fun isResolutionDowngrade(reference: SourceProfile, candidate: SourceProfile): Boolean =
    reference.resolutionHeight != null && candidate.resolutionHeight != null &&
        candidate.resolutionHeight < reference.resolutionHeight

private fun isResolutionUpgrade(reference: SourceProfile, candidate: SourceProfile): Boolean =
    reference.resolutionHeight != null && candidate.resolutionHeight != null &&
        candidate.resolutionHeight > reference.resolutionHeight

fun normalizeCompleteRelease(value: String?): String? = value
    ?.substringBefore('?')
    ?.substringBefore('#')
    ?.replace(Regex("(?i)\\.(mkv|mp4|avi|webm|m4v|ts|srt|ass|ssa|vtt)$"), "")
    ?.replace(Regex("[^a-zA-Z0-9]+"), " ")
    ?.trim()
    ?.lowercase()
    ?.takeIf { it.isNotBlank() }

fun parseReleaseYear(value: String?): Int? = Regex("(?:^|[^0-9])((?:19|20)\\d{2})(?:[^0-9]|$)")
    .find(value.orEmpty())
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()

fun parseSeasonNumber(value: String?): Int? {
    val text = value.orEmpty()
    return Regex("(?i)(?:^|[^a-z0-9])S(\\d{1,2})(?:[ ._-]*E\\d{1,3})?(?:$|[^a-z0-9])")
        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(?:^|[^a-z0-9])(\\d{1,2})x\\d{1,3}(?:$|[^a-z0-9])")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

fun parseEpisodeNumber(value: String?): Int? {
    val text = value.orEmpty()
    return Regex("(?i)(?:^|[^a-z0-9])S\\d{1,2}[ ._-]*E(\\d{1,3})(?:$|[^a-z0-9])")
        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)(?:^|[^a-z0-9])\\d{1,2}x(\\d{1,3})(?:$|[^a-z0-9])")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("(?i)\\bEP(?:ISODE)?[ ._-]*(\\d{1,3})\\b")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

fun parseReleaseFps(value: String?): Double? = Regex(
    "(?i)(?:^|[^0-9])(23\\.976|23\\.98|24(?:\\.000)?|25(?:\\.000)?|29\\.97|30(?:\\.000)?|50(?:\\.000)?|59\\.94|60(?:\\.000)?)(?:[ ._-]*fps)(?:[^0-9]|$)",
).find(value.orEmpty())?.groupValues?.getOrNull(1)?.toDoubleOrNull()

fun parseEdition(value: String?): String? {
    val text = value.orEmpty().uppercase()
    return when {
        text.contains("DIRECTOR'S CUT") || text.contains("DIRECTORS CUT") ||
            text.contains("DIRECTOR.CUT") || text.contains("DIRECTORS.CUT") ||
            text.contains("DIRECTOR_CUT") || text.contains("DIRECTORS_CUT") -> "directors-cut"
        text.contains("EXTENDED") -> "extended"
        text.contains("THEATRICAL") -> "theatrical"
        text.contains("UNRATED") -> "unrated"
        text.contains("IMAX") -> "imax"
        else -> null
    }
}
