package com.torve.data.subtitles

import com.torve.data.addon.MediaReleaseFingerprint
import com.torve.data.addon.normalizeCompleteRelease
import com.torve.data.addon.parseEdition
import com.torve.data.addon.parseEpisodeNumber
import com.torve.data.addon.parseReleaseFps
import com.torve.data.addon.parseReleaseYear
import com.torve.data.addon.parseSeasonNumber
import kotlinx.datetime.Clock
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

enum class SubtitleMatchTier(val priority: Int, val displayLabel: String) {
    EXACT_FILE(0, "EXACT FILE"),
    EXACT_RELEASE(1, "EXACT RELEASE"),
    STRONG_RELEASE_MATCH(2, "STRONG MATCH"),
    COMPATIBLE_RELEASE(3, "COMPATIBLE"),
    GENERIC_MATCH(4, "GENERIC"),
    POOR_MATCH(5, "POOR MATCH"),
    REJECTED(6, "REJECTED"),
}

enum class SubtitleSortMode {
    SMART_MATCH,
    RATING,
    DOWNLOADS,
    NEWEST,
}

/** Provider-neutral model. Nullable provider metadata remains unknown rather than becoming zero. */
data class SubtitleMetadata(
    val subtitleId: String?,
    val subtitleFileId: Int?,
    val provider: String,
    val language: String,
    val subtitleFilename: String?,
    val releaseName: String?,
    val fps: Double?,
    val rating: Double?,
    val voteCount: Int?,
    val downloadCount: Int?,
    val recentDownloadCount: Int?,
    val trustedUploader: Boolean?,
    val uploaderName: String?,
    val uploaderRank: String?,
    val hearingImpaired: Boolean?,
    val forced: Boolean?,
    val hd: Boolean?,
    val aiTranslated: Boolean?,
    val machineTranslated: Boolean?,
    val uploadDate: String?,
    val movieHashMatch: Boolean?,
    val providerFileHashes: Set<String> = emptySet(),
    val comments: String? = null,
    val subtitleFileCount: Int? = null,
    val format: String? = null,
    val directUrl: String? = null,
    val mimeType: String? = null,
    val mediaTitle: String? = null,
    val mediaYear: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val mediaImdbId: Int? = null,
    val parentImdbId: Int? = null,
    /** True only when the provider endpoint itself was scoped to this exact content/episode. */
    val identityMatchedByRequest: Boolean? = null,
)

data class SubtitleRankingReason(
    val description: String,
    val points: Int,
    val limitation: Boolean = points < 0,
)

enum class SubtitleEvidenceState {
    MATCH,
    UNKNOWN,
    MISMATCH,
}

data class SubtitleEvidence(
    val state: SubtitleEvidenceState,
    val description: String,
)

enum class SubtitleMatchQuality(val displayLabel: String) {
    BEST("BEST MATCH"),
    STRONG("STRONG MATCH"),
    GOOD("GOOD MATCH"),
    POSSIBLE("POSSIBLE MATCH"),
    WEAK("WEAK MATCH"),
}

data class RankedSubtitle(
    val subtitle: SubtitleMetadata,
    val tier: SubtitleMatchTier,
    /** Deterministic compatibility score, not a probability. */
    val subtitleMatchScore: Int,
    val subtitleQualityScore: Int,
    val reasons: List<SubtitleRankingReason>,
    val contentIdentityScore: Int = 0,
    /** Null means the provider supplied no usable release/timing evidence. */
    val releaseMatchScore: Int? = null,
    val syncConfidenceScore: Int = subtitleMatchScore,
    val matchQuality: SubtitleMatchQuality = SubtitleMatchQuality.POSSIBLE,
    val matchExplanation: String = "Release information unavailable",
    val evidence: List<SubtitleEvidence> = emptyList(),
) {
    val isAutoSelectable: Boolean
        get() = tier == SubtitleMatchTier.EXACT_FILE ||
            tier == SubtitleMatchTier.EXACT_RELEASE ||
            (tier == SubtitleMatchTier.STRONG_RELEASE_MATCH && subtitleMatchScore >= 88)
}

data class SubtitleSearchFilters(
    val languages: Set<String> = emptySet(),
    val strongMatchesOnly: Boolean = false,
    val minimumRating: Double? = null,
    val trustedUploadersOnly: Boolean = false,
    val hearingImpaired: Boolean? = null,
    val forced: Boolean? = null,
    val excludeAiTranslated: Boolean = false,
    val excludeMachineTranslated: Boolean = false,
)

data class ParsedSubtitleRelease(
    val normalizedCompleteRelease: String?,
    val titleStem: String?,
    val year: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val releaseGroup: String?,
    val resolutionHeight: Int?,
    val sourceType: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val dynamicRange: String?,
    val edition: String?,
    val repack: Boolean,
    val proper: Boolean,
    val fps: Double?,
)

/** Provider identifiers and opaque hashes are useful internally, but are not release metadata. */
fun humanReadableSubtitleName(value: String?): String? {
    val trimmed = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val withoutExtension = trimmed.replace(Regex("(?i)\\.(srt|ass|ssa|vtt|sub|mks)$"), "")
    if (withoutExtension.matches(Regex("\\d{5,}"))) return null
    if (withoutExtension.matches(Regex("[0-9 .,_-]{5,}"))) return null
    if (withoutExtension.matches(Regex("(?i)[0-9a-f]{24,}"))) return null
    if (withoutExtension.matches(Regex("(?i)(?:subtitle|sub)[ ._-]*\\d+"))) return null
    if (withoutExtension.matches(Regex("(?i)(?:subtitle|subtitles|unknown|untitled)"))) return null
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return null
    return trimmed
}

fun parseSubtitleRelease(value: String?): ParsedSubtitleRelease {
    val raw = humanReadableSubtitleName(value?.lineSequence()?.firstOrNull()).orEmpty()
    val withoutExtension = raw.replace(Regex("(?i)\\.(srt|ass|ssa|vtt|sub|mks)$"), "")
    val normalized = normalizeCompleteRelease(withoutExtension)
        ?.replace(Regex("(?i)\\s(?:eng|english|en|ger|german|deu|de)$"), "")
        ?.trim()
    val episodeToken = Regex(
        "(?i)(?:^|[^a-z0-9])(?:S\\d{1,2}[ ._-]*E\\d{1,3}|\\d{1,2}x\\d{1,3})(?:$|[^a-z0-9])",
    ).find(withoutExtension)
    val technical = Regex(
        "(?i)(?:^|[ ._-])(2160p|1080p|720p|480p|4k|uhd|web[ ._-]?dl|webrip|bluray|bdrip|hdtv|remux)(?:$|[ ._-])",
    ).find(withoutExtension)
    val titleEnd = episodeToken?.range?.first ?: technical?.range?.first ?: withoutExtension.length
    val titleStem = withoutExtension.substring(0, titleEnd)
        .replace(Regex("[^a-zA-Z0-9]+"), " ")
        .trim()
        .lowercase()
        .takeIf(String::isNotBlank)
    val releaseGroup = withoutExtension
        .takeIf { it.contains('-') }
        ?.substringAfterLast('-')
        ?.substringBefore('.')
        ?.replace(Regex("[^a-zA-Z0-9]+"), "")
        ?.lowercase()
        ?.takeIf { it.length in 2..32 && it.any(Char::isLetter) }
    val upper = withoutExtension.uppercase()
    return ParsedSubtitleRelease(
        normalizedCompleteRelease = normalized,
        titleStem = titleStem,
        year = parseReleaseYear(withoutExtension),
        seasonNumber = parseSeasonNumber(withoutExtension),
        episodeNumber = parseEpisodeNumber(withoutExtension),
        releaseGroup = releaseGroup,
        resolutionHeight = when {
            upper.contains("2160") || Regex("\\b4K\\b").containsMatchIn(upper) || upper.contains("UHD") -> 2160
            upper.contains("1080") -> 1080
            upper.contains("720") -> 720
            upper.contains("576") -> 576
            upper.contains("480") -> 480
            else -> null
        },
        sourceType = when {
            upper.contains("REMUX") -> "remux"
            Regex("WEB[ ._-]?DL").containsMatchIn(upper) -> "web-dl"
            Regex("WEB[ ._-]?RIP").containsMatchIn(upper) -> "webrip"
            upper.contains("BLURAY") || upper.contains("BLU-RAY") || upper.contains("BDRIP") -> "bluray"
            upper.contains("HDTV") -> "hdtv"
            upper.contains("DVDRIP") -> "dvdrip"
            else -> null
        },
        videoCodec = when {
            upper.contains("AV1") -> "av1"
            upper.contains("X265") || upper.contains("H265") || upper.contains("H.265") || upper.contains("HEVC") -> "hevc"
            upper.contains("X264") || upper.contains("H264") || upper.contains("H.264") || upper.contains("AVC") -> "h264"
            else -> null
        },
        audioCodec = when {
            upper.contains("TRUEHD") -> "truehd"
            upper.contains("DTS-HD") || upper.contains("DTSHD") -> "dts-hd"
            upper.contains("EAC3") || upper.contains("DDP") -> "ddp"
            Regex("(?:^|[ ._-])AC3(?:$|[ ._-])").containsMatchIn(upper) -> "ac3"
            upper.contains("AAC") -> "aac"
            else -> null
        },
        dynamicRange = when {
            upper.contains("DOLBY VISION") || Regex("(?:^|[ ._-])DV(?:$|[ ._-])").containsMatchIn(upper) -> "dolby-vision"
            upper.contains("HDR10+") -> "hdr10+"
            upper.contains("HDR10") -> "hdr10"
            upper.contains("HDR") -> "hdr"
            upper.contains("SDR") -> "sdr"
            else -> null
        },
        edition = parseEdition(withoutExtension),
        repack = upper.contains("REPACK"),
        proper = upper.contains("PROPER"),
        fps = parseReleaseFps(withoutExtension),
    )
}

class SubtitleIntelligence {
    fun rank(
        fingerprint: MediaReleaseFingerprint,
        candidates: List<SubtitleMetadata>,
        requestedSeason: Int? = fingerprint.seasonNumber,
        requestedEpisode: Int? = fingerprint.episodeNumber,
        expectedImdbId: String? = null,
        isSeries: Boolean = requestedEpisode != null,
        preferredLanguage: String? = null,
    ): List<RankedSubtitle> = candidates
        .map { score(fingerprint, it, requestedSeason, requestedEpisode, expectedImdbId, isSeries) }
        .sortedWith(smartComparator(preferredLanguage))

    fun applyFilters(
        ranked: List<RankedSubtitle>,
        filters: SubtitleSearchFilters,
        sortMode: SubtitleSortMode = SubtitleSortMode.SMART_MATCH,
    ): List<RankedSubtitle> {
        val filtered = ranked.filter { result ->
            val subtitle = result.subtitle
            (filters.languages.isEmpty() || subtitle.language.lowercase() in filters.languages.map(String::lowercase)) &&
                (!filters.strongMatchesOnly || result.tier.priority <= SubtitleMatchTier.STRONG_RELEASE_MATCH.priority) &&
                (filters.minimumRating == null || (subtitle.rating ?: -1.0) >= filters.minimumRating) &&
                (!filters.trustedUploadersOnly || subtitle.trustedUploader == true) &&
                (filters.hearingImpaired == null || subtitle.hearingImpaired == filters.hearingImpaired) &&
                (filters.forced == null || subtitle.forced == filters.forced) &&
                (!filters.excludeAiTranslated || subtitle.aiTranslated != true) &&
                (!filters.excludeMachineTranslated || subtitle.machineTranslated != true)
        }
        return when (sortMode) {
            SubtitleSortMode.SMART_MATCH -> filtered.sortedWith(smartComparator())
            SubtitleSortMode.RATING -> filtered.sortedWith(compareByDescending<RankedSubtitle> { it.subtitleQualityScore }
                .then(smartComparator()))
            SubtitleSortMode.DOWNLOADS -> filtered.sortedWith(compareByDescending<RankedSubtitle> { it.subtitle.downloadCount ?: -1 }
                .then(smartComparator()))
            SubtitleSortMode.NEWEST -> filtered.sortedWith(compareByDescending<RankedSubtitle> { it.subtitle.uploadDate.orEmpty() }
                .then(smartComparator()))
        }
    }

    fun score(
        fingerprint: MediaReleaseFingerprint,
        candidate: SubtitleMetadata,
        requestedSeason: Int? = fingerprint.seasonNumber,
        requestedEpisode: Int? = fingerprint.episodeNumber,
        expectedImdbId: String? = null,
        isSeries: Boolean = requestedEpisode != null,
    ): RankedSubtitle {
        val releaseText = humanReadableSubtitleName(candidate.releaseName)
            ?: humanReadableSubtitleName(candidate.subtitleFilename)
        val release = parseSubtitleRelease(releaseText)
        val candidateSeason = candidate.seasonNumber ?: release.seasonNumber
        val candidateEpisode = candidate.episodeNumber ?: release.episodeNumber
        val reasons = mutableListOf<SubtitleRankingReason>()
        val evidence = mutableListOf<SubtitleEvidence>()
        fun add(description: String, points: Int) { reasons += SubtitleRankingReason(description, points) }
        fun evidence(state: SubtitleEvidenceState, description: String) {
            evidence += SubtitleEvidence(state, description)
        }

        if (requestedSeason != null && candidateSeason != null && requestedSeason != candidateSeason) {
            return rejected(candidate, "Wrong season: expected S$requestedSeason, got S$candidateSeason")
        }
        if (requestedSeason != null && release.seasonNumber != null && requestedSeason != release.seasonNumber) {
            return rejected(candidate, "Release name conflicts with the requested season")
        }
        if (requestedEpisode != null && candidateEpisode != null && requestedEpisode != candidateEpisode) {
            return rejected(candidate, "Wrong episode: expected E$requestedEpisode, got E$candidateEpisode")
        }
        if (requestedEpisode != null && release.episodeNumber != null && requestedEpisode != release.episodeNumber) {
            return rejected(candidate, "Release name conflicts with the requested episode")
        }
        if (fingerprint.year != null && candidate.mediaYear != null && fingerprint.year != candidate.mediaYear) {
            return rejected(candidate, "Conflicting movie year")
        }
        val expectedNumericImdbId = expectedImdbId
            ?.removePrefix("tt")
            ?.trimStart('0')
            ?.toIntOrNull()
        val providerImdbId = if (isSeries) candidate.parentImdbId else candidate.mediaImdbId
        if (expectedNumericImdbId != null && providerImdbId != null && expectedNumericImdbId != providerImdbId) {
            return rejected(candidate, "Provider returned a different IMDb title")
        }
        val providerIdentityConfirmed = candidate.identityMatchedByRequest == true ||
            (expectedNumericImdbId != null && providerImdbId == expectedNumericImdbId)
        val activeTitle = fingerprint.parsedTitle
        val candidateTitle = candidate.mediaTitle?.let(::normalizeTitle) ?: release.titleStem
        if (activeTitle != null && candidateTitle != null && titlesClearlyConflict(activeTitle, candidateTitle)) {
            val labelHasReleaseEvidence = release.seasonNumber != null || release.episodeNumber != null ||
                release.sourceType != null || release.resolutionHeight != null || release.releaseGroup != null
            if (!providerIdentityConfirmed) return rejected(candidate, "Conflicting movie or series title")
            if (labelHasReleaseEvidence) {
                add("Provider title differs, but content identity is confirmed", -12)
            } else {
                add("Provider-scoped content identity is confirmed; label is not a release name", 0)
            }
        }

        val explicitEpisodeMatch = requestedEpisode != null && candidateEpisode == requestedEpisode &&
            (requestedSeason == null || candidateSeason == null || candidateSeason == requestedSeason)
        val identityKnown = providerIdentityConfirmed || explicitEpisodeMatch ||
            (requestedEpisode == null && !titlesClearlyConflict(activeTitle, candidateTitle))
        val contentIdentityScore = when {
            expectedNumericImdbId != null && providerImdbId == expectedNumericImdbId && explicitEpisodeMatch -> 100
            providerIdentityConfirmed && explicitEpisodeMatch -> 98
            explicitEpisodeMatch -> 94
            providerIdentityConfirmed -> 90
            requestedEpisode == null && identityKnown -> 82
            identityKnown -> 75
            else -> 35
        }
        if (identityKnown) {
            val identityDescription = when {
                requestedSeason != null && requestedEpisode != null ->
                    "Exact S${requestedSeason.toString().padStart(2, '0')}E${requestedEpisode.toString().padStart(2, '0')}"
                isSeries -> "Provider-confirmed episode"
                else -> "Matching movie identity"
            }
            evidence(SubtitleEvidenceState.MATCH, identityDescription)
            add(if (requestedEpisode != null) "Exact episode" else "Matching title", 28)
        } else {
            evidence(SubtitleEvidenceState.UNKNOWN, "Content identity is not confirmed")
        }

        if (candidate.movieHashMatch == true) {
            add("Provider-confirmed exact media hash", 100)
            evidence(SubtitleEvidenceState.MATCH, "Exact video file hash")
            return RankedSubtitle(
                subtitle = candidate,
                tier = SubtitleMatchTier.EXACT_FILE,
                subtitleMatchScore = 100,
                subtitleQualityScore = publicQualityScore(candidate),
                reasons = reasons,
                contentIdentityScore = 100,
                releaseMatchScore = null,
                syncConfidenceScore = 100,
                matchQuality = SubtitleMatchQuality.BEST,
                matchExplanation = "Exact video file hash match",
                evidence = evidence,
            )
        }

        val normalizedExact = fingerprint.normalizedCompleteRelease != null &&
            release.normalizedCompleteRelease != null &&
            fingerprint.normalizedCompleteRelease == release.normalizedCompleteRelease
        if (normalizedExact) add("Normalized exact release", 68)

        var releaseEvidenceKnown = false
        var releasePoints = 50
        if (normalizedExact) {
            releaseEvidenceKnown = true
            releasePoints = 100
            evidence(SubtitleEvidenceState.MATCH, "Exact release name")
        }

        val sameGroup = knownEqual(fingerprint.releaseGroup, release.releaseGroup)
        val groupConflict = fingerprint.releaseGroup != null && release.releaseGroup != null && !sameGroup
        when {
            sameGroup -> {
                releaseEvidenceKnown = true
                releasePoints += 22
                add("Release group ${fingerprint.releaseGroup}", 22)
                evidence(SubtitleEvidenceState.MATCH, "Release group ${fingerprint.releaseGroup}")
            }
            groupConflict -> {
                releaseEvidenceKnown = true
                releasePoints -= 18
                add("Different release group", -12)
                evidence(SubtitleEvidenceState.MISMATCH, "Release group differs")
            }
            fingerprint.releaseGroup != null -> evidence(SubtitleEvidenceState.UNKNOWN, "Release group unavailable")
        }

        val sameSource = knownEqual(fingerprint.releaseType, release.sourceType)
        val sourceConflict = fingerprint.releaseType != null && release.sourceType != null && !sameSource
        when {
            sameSource -> {
                releaseEvidenceKnown = true
                releasePoints += 20
                add("Source ${fingerprint.releaseType}", 14)
                evidence(SubtitleEvidenceState.MATCH, "${displayReleaseType(fingerprint.releaseType)} source")
            }
            sourceConflict -> {
                releaseEvidenceKnown = true
                releasePoints -= 28
                add("${release.sourceType} subtitle vs ${fingerprint.releaseType} video", -18)
                evidence(
                    SubtitleEvidenceState.MISMATCH,
                    "${displayReleaseType(release.sourceType)} subtitle for ${displayReleaseType(fingerprint.releaseType)} video",
                )
            }
            fingerprint.releaseType != null -> evidence(SubtitleEvidenceState.UNKNOWN, "Source type unavailable")
        }

        compareOptional(fingerprint.edition, release.edition)?.let { same ->
            releaseEvidenceKnown = true
            releasePoints += if (same) 14 else -32
            add(if (same) "Matching edition" else "Conflicting cut/edition", if (same) 10 else -28)
            evidence(
                if (same) SubtitleEvidenceState.MATCH else SubtitleEvidenceState.MISMATCH,
                if (same) "Matching edition" else "Edition/cut differs",
            )
        }
        val fpsCompatible = fpsCompatible(fingerprint.fps, candidate.fps ?: release.fps)
        when (fpsCompatible) {
            true -> {
                releaseEvidenceKnown = true
                releasePoints += 16
                add("Compatible FPS", 10)
                evidence(SubtitleEvidenceState.MATCH, "Compatible ${candidate.fps ?: release.fps} FPS")
            }
            false -> {
                releaseEvidenceKnown = true
                releasePoints -= 32
                add("Conflicting FPS family", -22)
                evidence(SubtitleEvidenceState.MISMATCH, "FPS differs")
            }
            null -> if (fingerprint.fps != null) evidence(SubtitleEvidenceState.UNKNOWN, "FPS unavailable")
        }
        val sameResolution = knownEqual(fingerprint.resolutionHeight, release.resolutionHeight)
        when {
            sameResolution -> {
                releaseEvidenceKnown = true
                releasePoints += 7
                add("Same resolution", 5)
                evidence(SubtitleEvidenceState.MATCH, "${fingerprint.resolutionHeight}p")
            }
            fingerprint.resolutionHeight != null && release.resolutionHeight != null -> {
                releaseEvidenceKnown = true
                releasePoints -= 3
                evidence(SubtitleEvidenceState.MISMATCH, "Resolution differs")
            }
            fingerprint.resolutionHeight != null -> evidence(SubtitleEvidenceState.UNKNOWN, "Resolution unavailable")
        }
        val sameCodec = knownEqual(fingerprint.codec, release.videoCodec)
        when {
            sameCodec -> {
                releaseEvidenceKnown = true
                releasePoints += 6
                add("Compatible video encode", 4)
                evidence(SubtitleEvidenceState.MATCH, displayCodec(fingerprint.codec))
            }
            fingerprint.codec != null && release.videoCodec != null -> {
                releaseEvidenceKnown = true
                releasePoints -= 3
                evidence(SubtitleEvidenceState.MISMATCH, "Video codec differs")
            }
            fingerprint.codec != null -> evidence(SubtitleEvidenceState.UNKNOWN, "Video codec unavailable")
        }
        if (fingerprint.repack == release.repack && (fingerprint.repack || release.repack)) {
            releaseEvidenceKnown = true
            releasePoints += 6
            add("Same REPACK family", 5)
        }
        if (fingerprint.proper == release.proper && (fingerprint.proper || release.proper)) {
            releaseEvidenceKnown = true
            releasePoints += 6
            add("Same PROPER family", 5)
        }

        val releaseMatchScore = if (releaseEvidenceKnown) releasePoints.coerceIn(0, 100) else null
        if (!releaseEvidenceKnown) {
            evidence(SubtitleEvidenceState.UNKNOWN, "Release timing information unavailable")
        }
        val materialReleaseConflict = sourceConflict || fpsCompatible == false || hasEditionConflict(fingerprint, release)
        val tier = when {
            normalizedExact && identityKnown -> SubtitleMatchTier.EXACT_RELEASE
            identityKnown && sameGroup && !sourceConflict && fpsCompatible != false &&
                !hasEditionConflict(fingerprint, release) -> SubtitleMatchTier.STRONG_RELEASE_MATCH
            identityKnown && releaseEvidenceKnown && !materialReleaseConflict &&
                (sameSource || sameGroup || fpsCompatible == true) ->
                SubtitleMatchTier.COMPATIBLE_RELEASE
            identityKnown && materialReleaseConflict -> SubtitleMatchTier.POOR_MATCH
            identityKnown -> SubtitleMatchTier.GENERIC_MATCH
            else -> SubtitleMatchTier.GENERIC_MATCH
        }
        val syncScore = when {
            normalizedExact && identityKnown -> 98
            releaseMatchScore != null ->
                ((contentIdentityScore * 0.35) + (releaseMatchScore * 0.65)).roundToInt().coerceIn(0, 97)
            else -> (contentIdentityScore * 0.38).roundToInt().coerceIn(0, 38)
        }
        val quality = when (tier) {
            SubtitleMatchTier.EXACT_FILE, SubtitleMatchTier.EXACT_RELEASE -> SubtitleMatchQuality.BEST
            SubtitleMatchTier.STRONG_RELEASE_MATCH -> SubtitleMatchQuality.STRONG
            SubtitleMatchTier.COMPATIBLE_RELEASE -> SubtitleMatchQuality.GOOD
            SubtitleMatchTier.GENERIC_MATCH -> SubtitleMatchQuality.POSSIBLE
            SubtitleMatchTier.POOR_MATCH, SubtitleMatchTier.REJECTED -> SubtitleMatchQuality.WEAK
        }
        val explanation = matchExplanation(
            identityKnown = identityKnown,
            requestedEpisode = requestedEpisode,
            normalizedExact = normalizedExact,
            sameGroup = sameGroup,
            groupConflict = groupConflict,
            sameSource = sameSource,
            sourceConflict = sourceConflict,
            activeSource = fingerprint.releaseType,
            subtitleSource = release.sourceType,
            fpsCompatible = fpsCompatible,
            releaseEvidenceKnown = releaseEvidenceKnown,
        )
        return RankedSubtitle(
            subtitle = candidate,
            tier = tier,
            subtitleMatchScore = syncScore,
            subtitleQualityScore = publicQualityScore(candidate),
            reasons = reasons,
            contentIdentityScore = contentIdentityScore,
            releaseMatchScore = releaseMatchScore,
            syncConfidenceScore = syncScore,
            matchQuality = quality,
            matchExplanation = explanation,
            evidence = evidence.distinct(),
        )
    }

    fun publicQualityScore(candidate: SubtitleMetadata): Int {
        val votes = candidate.voteCount?.coerceAtLeast(0)
        val rating = candidate.rating?.coerceIn(0.0, 10.0)
        var score = 0.0
        if (rating != null && votes != null) {
            val bayesian = ((rating * votes) + (QUALITY_PRIOR_RATING * QUALITY_PRIOR_WEIGHT)) /
                (votes + QUALITY_PRIOR_WEIGHT)
            score += bayesian * 6.5
            score += (votes.toDouble() / (votes + 50.0)) * 12.0
        } else if (rating != null) {
            score += rating * 4.0
        }
        candidate.downloadCount?.takeIf { it > 0 }?.let { downloads ->
            score += (ln(downloads.toDouble() + 1.0) / ln(10.0) * 3.5).coerceAtMost(14.0)
        }
        if (candidate.trustedUploader == true) score += 10.0
        if (candidate.machineTranslated == true) score -= 20.0
        if (candidate.aiTranslated == true) score -= 12.0
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun rejected(candidate: SubtitleMetadata, reason: String) = RankedSubtitle(
        subtitle = candidate,
        tier = SubtitleMatchTier.REJECTED,
        subtitleMatchScore = 0,
        subtitleQualityScore = publicQualityScore(candidate),
        reasons = listOf(SubtitleRankingReason(reason, -100, limitation = true)),
        contentIdentityScore = 0,
        releaseMatchScore = null,
        syncConfidenceScore = 0,
        matchQuality = SubtitleMatchQuality.WEAK,
        matchExplanation = reason,
        evidence = listOf(SubtitleEvidence(SubtitleEvidenceState.MISMATCH, reason)),
    )

    private fun smartComparator(preferredLanguage: String? = null) = compareBy<RankedSubtitle> { it.tier.priority }
        .thenByDescending { it.subtitleMatchScore }
        .thenByDescending {
            !preferredLanguage.isNullOrBlank() && subtitleLanguagesMatch(it.subtitle.language, preferredLanguage)
        }
        .thenByDescending { it.subtitleQualityScore }
        .thenByDescending { it.subtitle.trustedUploader == true }
        .thenByDescending { it.subtitle.voteCount ?: -1 }
        .thenByDescending { it.subtitle.downloadCount ?: -1 }
        .thenBy { it.subtitle.subtitleFilename.orEmpty() }

    private companion object {
        const val QUALITY_PRIOR_RATING = 7.0
        const val QUALITY_PRIOR_WEIGHT = 25.0
    }
}

data class SubtitleCacheKey(
    val contentId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val languageScope: String,
    val releaseFingerprint: String,
)

/** Small bounded session cache. Its key includes release identity so source switches invalidate relevance. */
class SubtitleSearchCache(
    private val ttlMs: Long = 10 * 60_000L,
    private val maxEntries: Int = 24,
) {
    private data class Entry(val createdAtMs: Long, val results: List<RankedSubtitle>)
    private val entries = linkedMapOf<SubtitleCacheKey, Entry>()

    fun get(key: SubtitleCacheKey, nowMs: Long = Clock.System.now().toEpochMilliseconds()): List<RankedSubtitle>? {
        val entry = entries[key] ?: return null
        if (nowMs - entry.createdAtMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.results
    }

    fun put(key: SubtitleCacheKey, results: List<RankedSubtitle>, nowMs: Long = Clock.System.now().toEpochMilliseconds()) {
        entries.remove(key)
        entries[key] = Entry(nowMs, results)
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}

fun MediaReleaseFingerprint.subtitleCacheFingerprint(): String = listOfNotNull(
    normalizedCompleteRelease,
    releaseGroup,
    resolutionHeight?.toString(),
    releaseType,
    codec,
    dynamicRange,
    edition,
    fps?.toString(),
    infoHash,
    movieHash,
).joinToString("|").ifBlank { sourceKey }

fun normalizeMediaTitle(value: String?): String? = value
    ?.replace(Regex("[^a-zA-Z0-9]+"), " ")
    ?.trim()
    ?.lowercase()
    ?.takeIf(String::isNotBlank)

private fun normalizeTitle(value: String): String = normalizeMediaTitle(value).orEmpty()

private fun titlesClearlyConflict(left: String?, right: String?): Boolean {
    if (left.isNullOrBlank() || right.isNullOrBlank()) return false
    val ignored = setOf("the", "a", "an", "and", "of", "in", "to", "part")
    val a = normalizeTitle(left).split(' ').filter { it.length > 1 && it !in ignored }.toSet()
    val b = normalizeTitle(right).split(' ').filter { it.length > 1 && it !in ignored }.toSet()
    if (a.isEmpty() || b.isEmpty()) return false
    return a.intersect(b).size < minOf(a.size, b.size).coerceAtMost(2)
}

private fun knownEqual(left: Any?, right: Any?): Boolean = left != null && right != null && left == right

private fun compareOptional(left: String?, right: String?): Boolean? =
    if (left == null || right == null) null else left == right

private fun fpsCompatible(left: Double?, right: Double?): Boolean? {
    if (left == null || right == null || left <= 0.0 || right <= 0.0) return null
    return abs(left - right) <= 0.08
}

private fun hasEditionConflict(fingerprint: MediaReleaseFingerprint, release: ParsedSubtitleRelease): Boolean =
    fingerprint.edition != null && release.edition != null && fingerprint.edition != release.edition

private fun displayReleaseType(value: String?): String = when (value?.lowercase()) {
    "web-dl" -> "WEB-DL"
    "webrip" -> "WEBRip"
    "bluray" -> "BluRay"
    "hdtv" -> "HDTV"
    "dvdrip" -> "DVDRip"
    "remux" -> "Remux"
    null -> "Unknown source"
    else -> value.orEmpty()
}

private fun displayCodec(value: String?): String = when (value?.lowercase()) {
    "h264" -> "H.264"
    "hevc" -> "H.265/HEVC"
    "av1" -> "AV1"
    null -> "Unknown codec"
    else -> value.orEmpty().uppercase()
}

private fun matchExplanation(
    identityKnown: Boolean,
    requestedEpisode: Int?,
    normalizedExact: Boolean,
    sameGroup: Boolean,
    groupConflict: Boolean,
    sameSource: Boolean,
    sourceConflict: Boolean,
    activeSource: String?,
    subtitleSource: String?,
    fpsCompatible: Boolean?,
    releaseEvidenceKnown: Boolean,
): String {
    val identity = if (requestedEpisode != null) "Correct episode" else "Correct title"
    return when {
        normalizedExact -> "Exact release match"
        !identityKnown -> "Content identity not confirmed"
        fpsCompatible == false -> "$identity · FPS differs"
        sourceConflict -> "$identity · ${displayReleaseType(subtitleSource)} subtitle for ${displayReleaseType(activeSource)} video"
        sameSource && sameGroup -> "Same ${displayReleaseType(activeSource)} release and release group"
        sameSource && groupConflict -> "${displayReleaseType(activeSource)} source · release group differs"
        sameSource -> "$identity · same ${displayReleaseType(activeSource)} source"
        !releaseEvidenceKnown -> "$identity · release match unknown"
        else -> "$identity · release information incomplete"
    }
}
