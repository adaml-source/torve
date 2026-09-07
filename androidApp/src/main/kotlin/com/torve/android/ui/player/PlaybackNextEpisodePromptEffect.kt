package com.torve.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.torve.domain.model.ContentWarmupTrigger
import com.torve.domain.model.MediaType
import com.torve.domain.model.NextEpisodeMode
import com.torve.domain.model.NextEpisodePreparationMode
import com.torve.domain.model.Season
import com.torve.domain.model.SourceAccelerationContext
import com.torve.domain.model.SourceAccelerationRequest
import com.torve.domain.model.StreamFetchPolicy
import com.torve.domain.player.NextEpisodeHelper
import com.torve.domain.player.NextEpisodeInfo
import com.torve.domain.player.PlaybackSegmentUiState
import com.torve.domain.repository.AddonRepository
import com.torve.presentation.settings.SettingsUiState
import com.torve.presentation.settings.SettingsViewModel
import com.torve.domain.repository.MetadataRepository
import com.torve.domain.repository.StreamRepository
import com.torve.platform.NetworkMonitor
import com.torve.platform.NetworkType
import kotlinx.coroutines.CancellationException

internal data class PlaybackSeasonLoadInput(
    val showTmdbId: Int?,
    val currentSeasonNumber: Int?,
    val mediaType: String,
)

@Composable
internal fun PlaybackSeasonLoadEffect(
    input: PlaybackSeasonLoadInput,
    metadataRepository: MetadataRepository,
    onLoaded: (List<Season>) -> Unit,
) {
    LaunchedEffect(input.showTmdbId, input.currentSeasonNumber) {
        val showId = input.showTmdbId?.takeIf { it > 0 } ?: return@LaunchedEffect
        val seasonNumber = input.currentSeasonNumber ?: return@LaunchedEffect
        if (input.mediaType != "tv") return@LaunchedEffect
        try {
            val detail = metadataRepository.getDetail("tv", showId)
            val seasonsToLoad = detail.seasons
                .filter { it.seasonNumber > 0 }
                .sortedBy { it.seasonNumber }
                .filter { it.seasonNumber == seasonNumber || it.seasonNumber == seasonNumber + 1 }
            onLoaded(
                seasonsToLoad.map { season ->
                    try {
                        metadataRepository.getSeasonDetail(showId, season.seasonNumber)
                    } catch (_: Exception) {
                        season
                    }
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Metadata failure keeps ordinary playback intact.
        }
    }
}

internal data class PlaybackNextEpisodePromptInput(
    val currentSeasonNumber: Int?,
    val currentEpisodeNumber: Int?,
    val currentPositionMs: Long,
    val durationMs: Long,
    val completionDetected: Boolean,
    val nextEpisodeCancelled: Boolean,
    val nextEpisodeInfo: NextEpisodeInfo?,
    val loadedSeasons: List<Season>,
    val segmentUiState: PlaybackSegmentUiState,
    val hasSegments: Boolean,
    val settingsState: SettingsUiState,
)

/** Keeps next-prompt policy out of the already complex player composition. */
@Composable
internal fun PlaybackNextEpisodePromptEffect(
    input: PlaybackNextEpisodePromptInput,
    settingsViewModel: SettingsViewModel,
    onNextEpisodeResolved: (NextEpisodeInfo) -> Unit,
    onShowPrompt: (Int) -> Unit,
) {
    LaunchedEffect(input.currentPositionMs, input.durationMs, input.completionDetected, input.segmentUiState) {
        val season = input.currentSeasonNumber ?: return@LaunchedEffect
        val episode = input.currentEpisodeNumber ?: return@LaunchedEffect
        if (input.durationMs <= 0L || input.nextEpisodeCancelled) return@LaunchedEffect

        val prefs = settingsViewModel.buildStreamPreferences()
        if (prefs.nextEpisodeMode == NextEpisodeMode.OFF) return@LaunchedEffect

        val remainingMs = input.durationMs - input.currentPositionMs
        val progressPercent = input.currentPositionMs.toFloat() / input.durationMs
        var resolvedNext = input.nextEpisodeInfo
        val shouldPrepare = progressPercent >= 0.75f || remainingMs in 1..10 * 60_000L
        if (shouldPrepare && resolvedNext == null) {
            resolvedNext = NextEpisodeHelper.getNextEpisode(
                currentSeason = season,
                currentEpisode = episode,
                seasons = input.loadedSeasons,
            )
            resolvedNext?.let(onNextEpisodeResolved)
        }

        if (resolvedNext == null || input.completionDetected) return@LaunchedEffect
        val shouldPrompt = shouldShowNextEpisodePrompt(
            detectedCreditsPrompt = input.segmentUiState.showNextPrompt,
            hasSegments = input.hasSegments,
            remainingMs = remainingMs,
            nextEpisodeMode = prefs.nextEpisodeMode,
            creditsActionMode = input.settingsState.playNextDuringCreditsMode,
        )
        if (shouldPrompt) {
            val countdown = if (input.segmentUiState.showNextPrompt) 15
                else ((remainingMs + 999L) / 1_000L).toInt().coerceIn(1, 15)
            onShowPrompt(countdown)
        }
    }
}

internal data class PlaybackNextEpisodeWarmupInput(
    val nextEpisodeInfo: NextEpisodeInfo?,
    val showImdbId: String?,
    val showTmdbId: Int?,
    val seriesTitle: String,
)

/** Starts bounded next-source preparation without expanding the main player composition. */
@Composable
internal fun PlaybackNextEpisodeWarmupEffect(
    input: PlaybackNextEpisodeWarmupInput,
    streamRepository: StreamRepository,
    addonRepository: AddonRepository,
    settingsViewModel: SettingsViewModel,
    networkMonitor: NetworkMonitor,
) {
    LaunchedEffect(
        input.nextEpisodeInfo?.seasonNumber,
        input.nextEpisodeInfo?.episodeNumber,
        input.showImdbId,
    ) {
        val nextEpisode = input.nextEpisodeInfo ?: return@LaunchedEffect
        val imdbId = input.showImdbId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val preferences = settingsViewModel.buildStreamPreferences()
        if (
            preferences.nextEpisodeMode == NextEpisodeMode.OFF ||
            preferences.nextEpisodePreparationMode == NextEpisodePreparationMode.OFF
        ) return@LaunchedEffect
        if (
            preferences.nextEpisodePreloadWifiOnly &&
            networkMonitor.currentNetworkType() !in setOf(NetworkType.WIFI, NetworkType.ETHERNET)
        ) return@LaunchedEffect

        try {
            val addons = try {
                addonRepository.getInstalledAddons()
            } catch (_: Exception) {
                emptyList()
            }
            streamRepository.warmupStartupCandidates(
                request = SourceAccelerationRequest(
                    mediaType = MediaType.SERIES,
                    imdbId = imdbId,
                    contentId = input.showTmdbId?.let { "tmdb:$it" },
                    title = input.seriesTitle,
                    seasonNumber = nextEpisode.seasonNumber,
                    episodeNumber = nextEpisode.episodeNumber,
                    context = SourceAccelerationContext(
                        addons = addons,
                        debridAccounts = settingsViewModel.getDebridAccounts(),
                        preferences = preferences,
                        startupFetchPolicy = StreamFetchPolicy.PLAYBACK_STARTUP,
                    ),
                ),
                trigger = ContentWarmupTrigger.NEXT_EPISODE_AUTOPLAY,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Preparation is opportunistic and never blocks playback.
        }
    }
}
