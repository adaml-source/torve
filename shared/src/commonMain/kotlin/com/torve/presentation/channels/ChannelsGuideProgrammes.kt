package com.torve.presentation.channels

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.programmesForEpgChannel
import com.torve.domain.model.stableChannelId

internal fun programmesForGuideChannel(state: ChannelsUiState, channel: Channel): List<EpgProgramme> {
    val playlistId = channel.playlistId.ifBlank { state.selectedPlaylistId.orEmpty() }
    if (playlistId.isBlank()) return emptyList()
    val schedule = programmesForEpgChannel(state.guideProgrammes, playlistId, channel)
        .ifEmpty { state.guideProgrammes[stableChannelId(playlistId, channel)].orEmpty() }
    return schedule.filter { it.endTime > it.startTime }.distinctBy { it.startTime }.sortedBy { it.startTime }
}

/** Display bounds only; programme details and catch-up keep the provider's original times. */
data class GuideProgrammeSlot(val programme: EpgProgramme, val startTime: Long, val endTime: Long)

fun guideProgrammeSlots(programmes: List<EpgProgramme>): List<GuideProgrammeSlot> {
    val slots = mutableListOf<GuideProgrammeSlot>()
    fun title(programme: EpgProgramme) = programme.title.trim().lowercase().replace(Regex("\\s+"), " ")
    // Prefer the complete entry at a duplicate start, independent of feed order.
    val sorted = programmes.filter { it.endTime > it.startTime }
        .sortedWith(compareBy<EpgProgramme> { it.startTime }.thenByDescending { it.endTime }.thenBy { it.title })
        .distinctBy { it.startTime }
    for (programme in sorted) {
        val previous = slots.lastOrNull()
        if (previous != null && programme.startTime < previous.endTime) {
            val overlap = minOf(previous.endTime, programme.endTime) - programme.startTime
            val shorterDuration = minOf(previous.endTime - previous.startTime, programme.endTime - programme.startTime)
            val previousEpisode = previous.programme.subTitle?.trim()?.takeIf { it.isNotEmpty() }
            val episode = programme.subTitle?.trim()?.takeIf { it.isNotEmpty() }
            val sameEpisode = previousEpisode == null || episode == null || previousEpisode.equals(episode, ignoreCase = true)
            val sameBroadcast = sameEpisode && title(previous.programme) == title(programme) &&
                overlap >= shorterDuration / 2 && programme.startTime - previous.startTime <= shorterDuration / 2
            if (sameBroadcast) {
                // Some providers publish the same programme twice with slightly shifted times.
                // A small boundary overlap between adjacent same-title episodes is not a duplicate.
                slots[slots.lastIndex] = previous.copy(endTime = maxOf(previous.endTime, programme.endTime))
                continue
            }
            // A later programme takes over the lane at its start, never on top of another card.
            slots[slots.lastIndex] = previous.copy(endTime = programme.startTime)
        }
        slots += GuideProgrammeSlot(programme, programme.startTime, programme.endTime)
    }
    return slots
}
