package com.torve.presentation.recording

import com.torve.data.recording.EpgCorrectionRepository
import com.torve.domain.recording.EpgCorrection
import com.torve.domain.recording.EpgHealth
import com.torve.domain.recording.computeEpgHealth
import com.torve.domain.model.EpgProgramme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * UI-side state for the EPG correction Settings panel.
 *
 * Loads / saves through [EpgCorrectionRepository]. State is keyed by
 * playlist id so switching playlists in the UI re-loads the right slot.
 *
 * **Three correction levers** (matching the data model):
 *   - [setOffsetMinutes] — shift every programme's start/end by N
 *     minutes. UI surfaces this as ± buttons + a numeric field.
 *   - [setMapping] — manual playlist channel id → EPG tvg-id remap.
 *     UI lists the unmatched channels with a dropdown of nearest EPG
 *     ids; this VM owns no fuzzy matcher.
 *   - [setHiddenCategories] — drop guide categories from view.
 *
 * The VM also surfaces an [EpgHealth] snapshot so the live page can
 * render a non-blocking "EPG looks stale" banner with a "Fix EPG" CTA.
 */
class EpgCorrectionViewModel(
    private val repository: EpgCorrectionRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow(EpgCorrectionUiState())
    val state: StateFlow<EpgCorrectionUiState> = _state.asStateFlow()

    /** Hot-load the correction for a playlist; safe to call repeatedly. */
    fun load(playlistId: String) {
        if (_state.value.correction.playlistId == playlistId &&
            _state.value.isHydrated
        ) return
        scope.launch {
            val correction = repository.get(playlistId)
            _state.value = _state.value.copy(correction = correction, isHydrated = true)
        }
    }

    fun setOffsetMinutes(value: Int) {
        val current = _state.value.correction
        val updated = current.copy(offsetMinutes = value)
        persist(updated)
    }

    fun setMapping(channelId: String, epgChannelId: String?) {
        val current = _state.value.correction
        val updatedRemap = current.tvgIdRemap.toMutableMap().apply {
            if (epgChannelId.isNullOrBlank() || epgChannelId == channelId) {
                remove(channelId)
            } else {
                put(channelId, epgChannelId)
            }
        }
        persist(current.copy(tvgIdRemap = updatedRemap))
    }

    fun setHiddenCategories(categories: Set<String>) {
        val current = _state.value.correction
        persist(current.copy(hiddenCategories = categories))
    }

    fun toggleHiddenCategory(category: String) {
        val current = _state.value.correction.hiddenCategories
        val updated = if (category in current) current - category else current + category
        setHiddenCategories(updated)
    }

    /**
     * Update the [EpgHealth] snapshot from the latest match-counts +
     * programme list. The live page calls this after every EPG load.
     */
    fun updateHealth(
        matchedChannelCount: Int,
        unmatchedChannelCount: Int,
        programmes: List<EpgProgramme>,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        val playlistId = _state.value.correction.playlistId
        val health = computeEpgHealth(
            playlistId = playlistId,
            matchedChannelCount = matchedChannelCount,
            unmatchedChannelCount = unmatchedChannelCount,
            programmes = programmes,
            nowMs = nowMs,
        )
        _state.value = _state.value.copy(health = health)
    }

    private fun persist(correction: EpgCorrection) {
        _state.value = _state.value.copy(correction = correction)
        scope.launch { repository.save(correction) }
    }
}

data class EpgCorrectionUiState(
    val correction: EpgCorrection = EpgCorrection(playlistId = ""),
    val isHydrated: Boolean = false,
    val health: EpgHealth? = null,
)
