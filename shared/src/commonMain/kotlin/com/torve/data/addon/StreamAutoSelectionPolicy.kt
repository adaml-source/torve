package com.torve.data.addon

import com.torve.domain.model.SourceLanguageMatchMode
import com.torve.domain.model.StreamPreferences
import com.torve.domain.model.StreamQuality
import com.torve.domain.model.UnknownSourceMetadataPolicy

data class StreamSelectionContext(
    val durationMs: Long? = null,
    val activeAudioLanguage: String? = null,
    val automatic: Boolean = true,
)

enum class StreamRejectionReason {
    BELOW_MINIMUM_QUALITY,
    ABOVE_MAXIMUM_QUALITY,
    BELOW_MINIMUM_SIZE_PER_HOUR,
    UNKNOWN_SIZE,
    LANGUAGE_MISMATCH,
    UNKNOWN_LANGUAGE,
    NOT_CACHED,
}

data class StreamPolicyDecision(
    val eligible: Boolean,
    val scoreAdjustment: Int = 0,
    val rejectionReason: StreamRejectionReason? = null,
    val explanation: String? = null,
)

/**
 * Shared automatic-source gate used by Play Best, autoplay, fallback and next-episode preparation.
 * Manual source lists may still display rejected rows together with [StreamPolicyDecision.explanation].
 */
object StreamAutoSelectionPolicy {
    fun evaluate(
        stream: ParsedStream,
        preferences: StreamPreferences,
        context: StreamSelectionContext = StreamSelectionContext(),
    ): StreamPolicyDecision {
        val quality = StreamQuality.fromString(stream.quality)
        if (quality != StreamQuality.UNKNOWN) {
            if (quality.rank < preferences.maxQuality.rank) {
                return rejected(StreamRejectionReason.ABOVE_MAXIMUM_QUALITY, "Above maximum quality")
            }
            if (quality.rank > preferences.minQuality.rank) {
                return rejected(StreamRejectionReason.BELOW_MINIMUM_QUALITY, "Below minimum quality")
            }
        }

        if (preferences.cachedOnly && stream.requiresDebridVerification() && !stream.isCached) {
            return rejected(StreamRejectionReason.NOT_CACHED, "Not cached")
        }

        var adjustment = 0
        val minimumPerHour = preferences.minSourceSizePerHourBytes.coerceAtLeast(0L)
        if (minimumPerHour > 0L && context.durationMs != null && context.durationMs > 0L) {
            val actualBytes = parseStreamSizeBytes(stream.size)
            if (actualBytes == null) {
                if (context.automatic && preferences.unknownSourceSizePolicy == UnknownSourceMetadataPolicy.REJECT) {
                    return rejected(StreamRejectionReason.UNKNOWN_SIZE, "Source size is unknown")
                }
                adjustment -= 8
            } else {
                val requiredBytes = minimumSourceBytes(minimumPerHour, context.durationMs)
                if (actualBytes < requiredBytes) {
                    return rejected(
                        StreamRejectionReason.BELOW_MINIMUM_SIZE_PER_HOUR,
                        "Below configured size-per-hour floor",
                    )
                }
                adjustment += 5
            }
        }

        val preferredLanguages = buildList {
            context.activeAudioLanguage?.let(::normalizeLanguageCode)?.let(::add)
            preferences.preferredAudioLanguages.mapNotNullTo(this, ::normalizeLanguageCode)
        }.distinct()
        if (preferredLanguages.isNotEmpty()) {
            val sourceLanguages = stream.languages.mapNotNull(::normalizeLanguageCode).toSet()
            if (sourceLanguages.isEmpty()) {
                if (
                    context.automatic &&
                    (preferences.sourceLanguageMatchMode == SourceLanguageMatchMode.REQUIRE ||
                        preferences.unknownSourceLanguagePolicy == UnknownSourceMetadataPolicy.REJECT)
                ) {
                    return rejected(StreamRejectionReason.UNKNOWN_LANGUAGE, "Source language is unknown")
                }
                adjustment -= 6
            } else {
                val preferredIndex = preferredLanguages.indexOfFirst(sourceLanguages::contains)
                if (preferredIndex < 0) {
                    if (context.automatic && preferences.sourceLanguageMatchMode == SourceLanguageMatchMode.REQUIRE) {
                        return rejected(StreamRejectionReason.LANGUAGE_MISMATCH, "Preferred audio language unavailable")
                    }
                    adjustment -= 10
                } else {
                    adjustment += (12 - preferredIndex * 3).coerceAtLeast(3)
                }
            }
        }

        return StreamPolicyDecision(eligible = true, scoreAdjustment = adjustment)
    }

    fun minimumSourceBytes(minimumPerHourBytes: Long, durationMs: Long): Long {
        if (minimumPerHourBytes <= 0L || durationMs <= 0L) return 0L
        return ((minimumPerHourBytes.toDouble() * durationMs.toDouble()) / 3_600_000.0).toLong()
    }

    private fun rejected(reason: StreamRejectionReason, explanation: String) =
        StreamPolicyDecision(eligible = false, rejectionReason = reason, explanation = explanation)
}

fun parseStreamSizeBytes(size: String?): Long? {
    val text = size?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
    val match = Regex("""([\d.]+)\s*(TIB|TB|GIB|GB|MIB|MB|KIB|KB)""").find(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2]) {
        "TIB", "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
        "GIB", "GB" -> 1024.0 * 1024.0 * 1024.0
        "MIB", "MB" -> 1024.0 * 1024.0
        "KIB", "KB" -> 1024.0
        else -> return null
    }
    return (value * multiplier).toLong()
}

fun normalizeLanguageCode(value: String?): String? {
    val normalized = value?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotBlank() } ?: return null
    return when (normalized) {
        "english", "eng" -> "en"
        "german", "deu", "ger" -> "de"
        "spanish", "spa" -> "es"
        "french", "fra", "fre" -> "fr"
        "italian", "ita" -> "it"
        "portuguese", "por" -> "pt"
        "turkish", "tur" -> "tr"
        else -> normalized.takeIf { it.length in 2..3 }
    }
}
