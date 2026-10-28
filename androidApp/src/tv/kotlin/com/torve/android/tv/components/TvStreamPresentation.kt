package com.torve.android.tv.components

import com.torve.data.addon.ParsedStream
import com.torve.data.addon.isUsenetStream
import com.torve.data.usenet.model.UsenetAvailability
import com.torve.data.usenet.model.UsenetCandidateUiModel
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupConfidenceReasonCode
import java.util.Locale

internal enum class TvStreamAvailability(val label: String) {
    READY("Ready now"),
    PREPARING("Preparing"),
    UNAVAILABLE("Unavailable"),
    CHECK_ON_PLAY("Check on play"),
    UNCONFIRMED("Availability unconfirmed"),
    DIRECT("Direct source"),
}

internal data class TvStreamPresentation(
    val summary: String,
    val availability: TvStreamAvailability,
    val provider: String,
    val reason: String?,
    val filename: String,
    val technicalDetails: String,
    val streamLanguages: ParsedStream? = null,
)

/** Describe known facts; a resolver URL alone is not proof that a file is ready. */
internal fun tvStreamPresentation(
    stream: ParsedStream,
    startup: StartupCandidate? = null,
    usenet: UsenetCandidateUiModel? = null,
): TvStreamPresentation {
    val availability = when {
        usenet != null -> when (usenet.availabilityState) {
            UsenetAvailability.READY -> TvStreamAvailability.READY
            UsenetAvailability.PREPARING -> TvStreamAvailability.PREPARING
            UsenetAvailability.UNAVAILABLE -> TvStreamAvailability.UNAVAILABLE
        }
        startup?.readinessState == ReadinessState.UNAVAILABLE ||
            startup?.readinessState == ReadinessState.EMPTY -> TvStreamAvailability.UNAVAILABLE
        startup?.readinessState == ReadinessState.LOOKUP_ONLY -> TvStreamAvailability.UNCONFIRMED
        startup?.readinessState == ReadinessState.READY_NOW -> TvStreamAvailability.READY
        startup?.readinessState == ReadinessState.READY_WITH_RESOLVE -> TvStreamAvailability.CHECK_ON_PLAY
        stream.isCached -> TvStreamAvailability.READY
        stream.isUsenetStream() -> TvStreamAvailability.CHECK_ON_PLAY
        !stream.directUrl.isNullOrBlank() -> TvStreamAvailability.DIRECT
        else -> TvStreamAvailability.CHECK_ON_PLAY
    }
    val languages = com.torve.data.addon.SourceLanguages.codes(stream).mapNotNull(::tvStreamLanguageName).distinct()
    val quality = stream.quality.trim().takeUnless {
        it.isBlank() || it.equals("unknown", ignoreCase = true)
    } ?: "Quality unknown"
    val size = stream.size?.trim()?.takeIf { it.isNotEmpty() }
        ?: usenet?.sizeLabel?.takeIf { it.isNotBlank() }
        ?: startup?.sizeBytes?.takeIf { it > 0 }?.let(::tvStreamByteSize)
        ?: "Size unknown"
    val reasons = startup?.confidenceReasons.orEmpty().toSet()
    val reason = when {
        availability == TvStreamAvailability.UNAVAILABLE -> "Try another source."
        availability == TvStreamAvailability.PREPARING -> "Your provider is preparing this file."
        stream.recentSuccessCount > 0 || StartupConfidenceReasonCode.RECENT_SUCCESS in reasons ->
            "Played successfully before"
        availability == TvStreamAvailability.READY &&
            (stream.isCached || startup?.isKnownCached == true || StartupConfidenceReasonCode.HASH_CACHED in reasons) ->
            "Cached at your provider"
        StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH in reasons ||
            startup?.provenance?.kind == CandidateProvenanceKind.INVENTORY_MATCH ->
            "Found in your connected account"
        availability == TvStreamAvailability.READY &&
            (startup?.isDirectPlayback == true || StartupConfidenceReasonCode.DIRECT_PLAYABLE_URL in reasons) ->
            "Direct playback available"
        else -> null
    }
    return TvStreamPresentation(
        streamLanguages = stream,
        summary = listOf(quality, languages.joinToString(" + ").ifBlank { "Language unknown" }, size)
            .joinToString(" · "),
        availability = availability,
        provider = listOfNotNull(
            stream.addonName.trim().takeIf { it.isNotEmpty() },
            stream.source?.let(::tvStreamProviderLabel)?.takeIf { it.isNotEmpty() },
        ).distinct().joinToString(" · "),
        reason = reason,
        filename = stream.title.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
        technicalDetails = listOfNotNull(
            stream.codec?.takeIf { it.isNotBlank() },
            stream.hdr?.takeIf { it.isNotBlank() },
            stream.audioCodec?.takeIf { it.isNotBlank() },
            stream.seeds?.takeIf { it > 0 }?.let { "$it seeds" },
        ).distinct().joinToString(" · "),
    )
}

private fun tvStreamLanguageName(raw: String): String? {
    val code = raw.trim().replace('_', '-').lowercase(Locale.ROOT)
    if (code.isBlank() || code in setOf("unknown", "und")) return null
    if (code in setOf("multi", "mul")) return "Multiple languages"
    val language = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    return language.takeIf { it.isNotBlank() } ?: raw.trim()
}

private fun tvStreamByteSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    else -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
}

/** Add-on source descriptions can contain entire subtitle inventories after the provider. */
internal fun tvStreamProviderLabel(raw: String): String = raw.lineSequence().firstOrNull().orEmpty()
    .substringBefore("💾").substringBefore("🗣").substringBefore("💬").substringBefore("🗨")
    .trim().trimEnd('·').trim()
