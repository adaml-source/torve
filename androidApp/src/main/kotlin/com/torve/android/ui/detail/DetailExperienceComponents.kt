package com.torve.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Ash
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Emerald
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Snow
import com.torve.data.addon.ParsedStream
import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.MediaRatings
import com.torve.domain.model.RatingDisplayPrefs
import com.torve.domain.model.RatingSource
import com.torve.domain.model.ReadinessState
import com.torve.domain.model.hasValueFor
import com.torve.domain.model.StartupCandidate
import com.torve.domain.model.StartupConfidenceReasonCode
import com.torve.domain.player.StartupPlaybackPolicy
import com.torve.presentation.detail.PlaybackStartupPhase
import com.torve.presentation.detail.PlaybackStartupStatus

internal data class DetailPlaybackOptionGroup(
    val title: String,
    val subtitle: String? = null,
    val items: List<ParsedStream>,
)

private data class DetailPlaybackSummary(
    val total: Int,
    val readyNow: Int,
    val fastStart: Int,
    val cached: Int,
    val direct: Int,
    val recommended: Int,
    val more: Int,
)

private data class StreamBadge(
    val label: String,
    val tone: Color,
)

internal fun groupPlaybackOptionStreams(
    streams: List<ParsedStream>,
    startupCandidates: List<StartupCandidate> = emptyList(),
): List<DetailPlaybackOptionGroup> {
    val startupCandidateMap = startupCandidates.associateBy { it.streamKey }
    val fastStartKeys = StartupPlaybackPolicy.highConfidenceCandidateKeys(startupCandidates)
    val fastStart = streams.filter { it.streamUiKey() in fastStartKeys }
    val fastStartKeySet = fastStart.mapTo(linkedSetOf()) { it.streamUiKey() }

    val bestFit = streams
        .filterNot { it.streamUiKey() in fastStartKeySet }
        .filter { stream ->
            stream.score >= 70 || startupCandidateMap[stream.streamUiKey()] != null
        }
    val bestFitKeys = bestFit.mapTo(linkedSetOf()) { it.streamUiKey() }

    val more = streams.filterNot { stream ->
        val key = stream.streamUiKey()
        key in fastStartKeySet || key in bestFitKeys
    }

    return buildList {
        if (fastStart.isNotEmpty()) {
            add(
                DetailPlaybackOptionGroup(
                    title = "Fast Start",
                    subtitle = "Trusted quick-start options",
                    items = fastStart,
                ),
            )
        }
        if (bestFit.isNotEmpty()) {
            add(
                DetailPlaybackOptionGroup(
                    title = if (fastStart.isEmpty()) "Best Fit" else "More Great Matches",
                    subtitle = "Ranked for this device",
                    items = bestFit,
                ),
            )
        }
        if (more.isNotEmpty()) {
            add(
                DetailPlaybackOptionGroup(
                    title = "More Options",
                    subtitle = "Extra fallbacks and alternates",
                    items = more,
                ),
            )
        }
    }
}

@Composable
internal fun DetailPlaybackReadinessCard(
    streams: List<ParsedStream>,
    startupCandidates: List<StartupCandidate> = emptyList(),
    startupStatus: PlaybackStartupStatus? = null,
    isLoadingStreams: Boolean,
    isResolving: Boolean,
    isLoadingMoreSources: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val summary = summarizePlaybackOptions(streams, startupCandidates)
    val primary = when {
        isResolving -> "Opening the best playback option."
        startupStatus?.phase == PlaybackStartupPhase.LOADING_STARTUP_CANDIDATES -> "Preparing quick-start playback options."
        startupStatus?.phase == PlaybackStartupPhase.ATTEMPTING_STARTUP_AUTOPLAY -> "Trying the fastest trusted option."
        startupStatus?.phase == PlaybackStartupPhase.STARTUP_CANDIDATE_FAILED -> "Checking more playback options."
        startupStatus?.phase == PlaybackStartupPhase.FALLING_BACK_TO_FULL_FETCH -> "Loading more playback options."
        isLoadingStreams && streams.isEmpty() -> "Checking connected services and ranking playback options."
        summary.total > 0 && summary.fastStart > 0 && isLoadingMoreSources ->
            "${summary.fastStart} fast-start options ready. Checking more in the background."
        summary.total > 0 ->
            "${summary.fastStart} fast start • ${summary.readyNow} ready now • ${summary.more} more options"
        else -> null
    } ?: return

    val secondary = when {
        isResolving -> "Torve keeps ranked fallbacks ready if this first attempt does not start cleanly."
        startupStatus?.phase == PlaybackStartupPhase.STARTUP_CANDIDATE_FAILED ->
            "The quickest option did not start, so broader results are being checked."
        startupStatus?.phase == PlaybackStartupPhase.FALLING_BACK_TO_FULL_FETCH || isLoadingMoreSources ->
            "Quick-start options stay visible while more playback choices are added."
        isLoadingStreams -> "Immediate options are surfaced first, then broader results are ranked for this device."
        else -> buildString {
            val parts = mutableListOf<String>()
            if (summary.cached > 0) parts += "${summary.cached} cached"
            if (summary.direct > 0) parts += "${summary.direct} direct"
            if (summary.recommended > 0) parts += "${summary.recommended} best fit"
            if (summary.total > 0) parts += "${summary.total} total"
            append(parts.joinToString(" • "))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Graphite, RoundedCornerShape(14.dp))
            .border(1.dp, Gunmetal.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = primary,
            style = MaterialTheme.typography.bodyMedium,
            color = Snow,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = secondary,
            style = MaterialTheme.typography.bodySmall,
            color = Ash,
        )
        if (summary.total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailExperienceBadge(
                    label = "Fast ${summary.fastStart}",
                    tone = if (summary.fastStart > 0) Amber else Gunmetal,
                )
                DetailExperienceBadge(
                    label = "Ready ${summary.readyNow}",
                    tone = if (summary.readyNow > 0) Emerald else Gunmetal,
                )
                DetailExperienceBadge(
                    label = "Best fit ${summary.recommended}",
                    tone = if (summary.recommended > 0) Snow else Gunmetal,
                )
            }
        }
    }
}

@Composable
internal fun DetailRatingsAttribution(
    ratings: MediaRatings,
    prefs: RatingDisplayPrefs,
    modifier: Modifier = Modifier,
) {
    val activeProviders = activeRatingProviders(ratings, prefs)
    val torveInputs = prefs.torveWeights
        .filterValues { it > 0 }
        .keys
        .filter { hasRatingValue(it, ratings) }
    if (activeProviders.isEmpty() && (!prefs.showTorveScoreOnDetailPage || torveInputs.isEmpty())) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Charcoal.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (activeProviders.isNotEmpty()) {
            Text(
                text = "Ratings shown from ${activeProviders.joinToString(", ") { it.displayName }}.",
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
            )
        }
        if (prefs.showTorveScoreOnDetailPage && torveInputs.isNotEmpty()) {
            Text(
                text = "Torve Score blends ${torveInputs.joinToString(", ") { it.displayName }} using your selected weights.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreamExperienceBadges(
    stream: ParsedStream,
    startupCandidate: StartupCandidate? = null,
    modifier: Modifier = Modifier,
) {
    val badges = badgesForStream(stream, startupCandidate).take(3)
    if (badges.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        badges.forEach { badge ->
            DetailExperienceBadge(
                label = badge.label,
                tone = badge.tone,
            )
        }
    }
}

@Composable
internal fun StreamReadinessLabel(
    stream: ParsedStream,
    startupCandidate: StartupCandidate? = null,
    modifier: Modifier = Modifier,
) {
    val text = when {
        startupCandidate != null && StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(startupCandidate) -> "Fast start"
        stream.isReadyNow() -> "Ready now"
        startupCandidate?.readinessState == ReadinessState.READY_WITH_RESOLVE -> "Ready when picked"
        startupCandidate?.readinessState == ReadinessState.LOOKUP_ONLY -> "Additional option"
        else -> null
    } ?: return

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = when (text) {
            "Fast start" -> Amber
            "Ready now" -> Emerald
            else -> Ash
        },
        modifier = modifier,
    )
}

@Composable
private fun DetailExperienceBadge(
    label: String,
    tone: Color,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = tone,
        modifier = Modifier
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun badgesForStream(
    stream: ParsedStream,
    startupCandidate: StartupCandidate?,
): List<StreamBadge> {
    val badges = linkedMapOf<String, StreamBadge>()
    val reasons = startupCandidate?.confidenceReasons.orEmpty().toSet()

    if (startupCandidate != null && StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(startupCandidate)) {
        badges["Fast start"] = StreamBadge("Fast start", Amber)
    }
    if (reasons.contains(StartupConfidenceReasonCode.RECENT_SUCCESS)) {
        badges["Recent success"] = StreamBadge("Recent success", Emerald)
    }
    if (
        reasons.contains(StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH) ||
        startupCandidate?.provenance?.kind == CandidateProvenanceKind.INVENTORY_MATCH
    ) {
        badges["In your cloud"] = StreamBadge("In your cloud", Emerald)
    }
    if (stream.isCached || reasons.contains(StartupConfidenceReasonCode.HASH_CACHED)) {
        badges["Cached"] = StreamBadge("Cached", Emerald)
    }
    if (stream.isDirectCandidate() || reasons.contains(StartupConfidenceReasonCode.DIRECT_PLAYABLE_URL)) {
        badges["Direct"] = StreamBadge("Direct", Amber)
    }
    if (stream.score >= 85) {
        badges["Best fit"] = StreamBadge("Best fit", Snow)
    }

    return badges.values.toList()
}

private fun summarizePlaybackOptions(
    streams: List<ParsedStream>,
    startupCandidates: List<StartupCandidate>,
): DetailPlaybackSummary {
    val fastStartKeys = StartupPlaybackPolicy.highConfidenceCandidateKeys(startupCandidates)
    val recommendedKeys = streams
        .filter { it.score >= 85 }
        .mapTo(linkedSetOf()) { it.streamUiKey() }
    val readyNow = streams.count { it.isReadyNow() }
    val cached = streams.count { it.isCached }
    val direct = streams.count { it.isDirectCandidate() }
    val fastStart = streams.count { it.streamUiKey() in fastStartKeys }
    val recommended = recommendedKeys.size
    val highlightedKeys = linkedSetOf<String>().apply {
        addAll(fastStartKeys)
        addAll(recommendedKeys)
    }
    val more = (streams.size - highlightedKeys.size).coerceAtLeast(0)
    return DetailPlaybackSummary(
        total = streams.size,
        readyNow = readyNow,
        fastStart = fastStart,
        cached = cached,
        direct = direct,
        recommended = recommended,
        more = more,
    )
}

private fun activeRatingProviders(
    ratings: MediaRatings,
    prefs: RatingDisplayPrefs,
): List<RatingSource> {
    return prefs.providerOrder.filter { it in prefs.enabledProviders && hasRatingValue(it, ratings) }
}

private fun hasRatingValue(
    source: RatingSource,
    ratings: MediaRatings,
): Boolean {
    return ratings.hasValueFor(source)
}

private fun ParsedStream.isDirectCandidate(): Boolean = directUrl != null && infoHash == null

private fun ParsedStream.isReadyNow(): Boolean = isCached || isDirectCandidate()

internal fun ParsedStream.streamUiKey(): String =
    accelerationSourceKey ?: infoHash ?: directUrl ?: "$addonName|$title|$quality|${source.orEmpty()}"
