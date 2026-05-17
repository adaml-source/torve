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
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
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
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int? = null,
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

private data class StreamBadgeLabels(
    val fastStart: String,
    val recentSuccess: String,
    val inYourCloud: String,
    val cached: String,
    val direct: String,
    val bestFit: String,
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
                    titleRes = R.string.stream_group_fast_start,
                    subtitleRes = R.string.stream_group_fast_start_subtitle,
                    items = fastStart,
                ),
            )
        }
        if (bestFit.isNotEmpty()) {
            add(
                DetailPlaybackOptionGroup(
                    titleRes = if (fastStart.isEmpty()) R.string.stream_group_best_fit else R.string.stream_group_more_great_matches,
                    subtitleRes = R.string.stream_group_ranked_for_device,
                    items = bestFit,
                ),
            )
        }
        if (more.isNotEmpty()) {
            add(
                DetailPlaybackOptionGroup(
                    titleRes = R.string.stream_group_more_options,
                    subtitleRes = R.string.stream_group_more_options_subtitle,
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
    val summaryParts = listOfNotNull(
        if (summary.cached > 0) stringResource(R.string.detail_cached_count, summary.cached) else null,
        if (summary.direct > 0) stringResource(R.string.detail_direct_count, summary.direct) else null,
        if (summary.recommended > 0) stringResource(R.string.detail_best_fit_count, summary.recommended) else null,
        if (summary.total > 0) stringResource(R.string.detail_total_count, summary.total) else null,
    ).joinToString(" • ")
    val primary = when {
        isResolving -> stringResource(R.string.detail_opening_best_option)
        startupStatus?.phase == PlaybackStartupPhase.LOADING_STARTUP_CANDIDATES -> stringResource(R.string.detail_preparing_quick_start)
        startupStatus?.phase == PlaybackStartupPhase.ATTEMPTING_STARTUP_AUTOPLAY -> stringResource(R.string.detail_trying_fastest_option)
        startupStatus?.phase == PlaybackStartupPhase.STARTUP_CANDIDATE_FAILED -> stringResource(R.string.detail_checking_more_options)
        startupStatus?.phase == PlaybackStartupPhase.FALLING_BACK_TO_FULL_FETCH -> stringResource(R.string.detail_loading_more_options)
        isLoadingStreams && streams.isEmpty() -> stringResource(R.string.detail_checking_services)
        summary.total > 0 && summary.fastStart > 0 && isLoadingMoreSources ->
            stringResource(R.string.detail_fast_start_ready_checking_more, summary.fastStart)
        summary.total > 0 ->
            stringResource(R.string.detail_playback_summary, summary.fastStart, summary.readyNow, summary.more)
        else -> null
    } ?: return

    val secondary = when {
        isResolving -> stringResource(R.string.detail_ranked_fallbacks_ready)
        startupStatus?.phase == PlaybackStartupPhase.STARTUP_CANDIDATE_FAILED ->
            stringResource(R.string.detail_quickest_option_failed)
        startupStatus?.phase == PlaybackStartupPhase.FALLING_BACK_TO_FULL_FETCH || isLoadingMoreSources ->
            stringResource(R.string.detail_quick_start_stays_visible)
        isLoadingStreams -> stringResource(R.string.detail_immediate_options_first)
        else -> summaryParts
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
                    label = stringResource(R.string.detail_fast_badge, summary.fastStart),
                    tone = if (summary.fastStart > 0) Amber else Gunmetal,
                )
                DetailExperienceBadge(
                    label = stringResource(R.string.detail_ready_badge, summary.readyNow),
                    tone = if (summary.readyNow > 0) Emerald else Gunmetal,
                )
                DetailExperienceBadge(
                    label = stringResource(R.string.detail_best_fit_badge, summary.recommended),
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
                text = stringResource(
                    R.string.detail_ratings_shown_from,
                    activeProviders.joinToString(", ") { it.displayName },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Ash,
            )
        }
        if (prefs.showTorveScoreOnDetailPage && torveInputs.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.detail_torve_score_blends,
                    torveInputs.joinToString(", ") { it.displayName },
                ),
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
    val labels = StreamBadgeLabels(
        fastStart = stringResource(R.string.detail_fast_start),
        recentSuccess = stringResource(R.string.detail_recent_success),
        inYourCloud = stringResource(R.string.detail_in_your_cloud),
        cached = stringResource(R.string.detail_cached),
        direct = stringResource(R.string.detail_direct),
        bestFit = stringResource(R.string.detail_best_fit),
    )
    val badges = badgesForStream(stream, startupCandidate, labels).take(3)
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
    val isFastStart = startupCandidate != null && StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(startupCandidate)
    val isReadyNow = stream.isReadyNow()
    val text = when {
        isFastStart -> stringResource(R.string.detail_fast_start)
        isReadyNow -> stringResource(R.string.detail_ready_now)
        startupCandidate?.readinessState == ReadinessState.READY_WITH_RESOLVE -> stringResource(R.string.detail_ready_when_picked)
        startupCandidate?.readinessState == ReadinessState.LOOKUP_ONLY -> stringResource(R.string.detail_additional_option)
        else -> null
    } ?: return

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            isFastStart -> Amber
            isReadyNow -> Emerald
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
    labels: StreamBadgeLabels,
): List<StreamBadge> {
    val badges = linkedMapOf<String, StreamBadge>()
    val reasons = startupCandidate?.confidenceReasons.orEmpty().toSet()

    if (startupCandidate != null && StartupPlaybackPolicy.isHighConfidenceAutoplayCandidate(startupCandidate)) {
        badges[labels.fastStart] = StreamBadge(labels.fastStart, Amber)
    }
    if (reasons.contains(StartupConfidenceReasonCode.RECENT_SUCCESS)) {
        badges[labels.recentSuccess] = StreamBadge(labels.recentSuccess, Emerald)
    }
    if (
        reasons.contains(StartupConfidenceReasonCode.CONNECTED_SERVICE_MATCH) ||
        startupCandidate?.provenance?.kind == CandidateProvenanceKind.INVENTORY_MATCH
    ) {
        badges[labels.inYourCloud] = StreamBadge(labels.inYourCloud, Emerald)
    }
    if (stream.isCached || reasons.contains(StartupConfidenceReasonCode.HASH_CACHED)) {
        badges[labels.cached] = StreamBadge(labels.cached, Emerald)
    }
    if (stream.isDirectCandidate() || reasons.contains(StartupConfidenceReasonCode.DIRECT_PLAYABLE_URL)) {
        badges[labels.direct] = StreamBadge(labels.direct, Amber)
    }
    if (stream.score >= 85) {
        badges[labels.bestFit] = StreamBadge(labels.bestFit, Snow)
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

private fun ParsedStream.isDirectCandidate(): Boolean = directUrl != null

private fun ParsedStream.isReadyNow(): Boolean = isCached || isDirectCandidate()

internal fun ParsedStream.streamUiKey(): String =
    accelerationMemoryId ?: accelerationSourceKey ?: directUrl ?: magnetUrl ?: infoHash ?: "$addonName|$title|$quality|${source.orEmpty()}"
