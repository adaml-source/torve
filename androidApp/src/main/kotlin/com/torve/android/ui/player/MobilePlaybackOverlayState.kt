package com.torve.android.ui.player

import com.torve.domain.model.Channel
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.channelIdentityCandidates
import com.torve.presentation.channels.CategoryNameCleaner
import com.torve.presentation.channels.ChannelsUiState

internal sealed interface MobilePlaybackSheet {
    data object QuickActions : MobilePlaybackSheet
    data object Settings : MobilePlaybackSheet
    data object StreamInfo : MobilePlaybackSheet
    data object CurrentChannelGuide : MobilePlaybackSheet
    data object Guide : MobilePlaybackSheet
    data object ChannelList : MobilePlaybackSheet
    data class Picker(val type: MobilePlaybackPicker) : MobilePlaybackSheet
}

internal enum class MobilePlaybackPicker {
    AUDIO_TRACK,
    SUBTITLE_TRACK,
    AUDIO_DELAY,
    PLAYBACK_SPEED,
    PICTURE_FORMAT,
    LIVE_AUDIO_MODE,
    LIVE_BUFFER,
}

internal enum class MobilePlaybackSetting {
    AUDIO_DELAY,
    PLAYBACK_SPEED,
    PICTURE_FORMAT,
    LIVE_AUDIO_MODE,
    LIVE_BUFFER,
}

internal sealed interface MobilePlaybackBackResult {
    data class Update(
        val controlsVisible: Boolean,
        val sheetStack: List<MobilePlaybackSheet>,
    ) : MobilePlaybackBackResult

    data object ExitPlayer : MobilePlaybackBackResult
}

internal data class ResolvedLivePlaybackContext(
    val currentChannel: Channel? = null,
    val currentEnrichedChannel: EnrichedChannel? = null,
    val currentProgramme: EpgProgramme? = null,
    val nextProgramme: EpgProgramme? = null,
    val currentCategoryName: String? = null,
    val isFavorite: Boolean = false,
)

internal fun reduceMobilePlaybackBack(
    controlsVisible: Boolean,
    sheetStack: List<MobilePlaybackSheet>,
): MobilePlaybackBackResult {
    if (sheetStack.isNotEmpty()) {
        return MobilePlaybackBackResult.Update(
            controlsVisible = true,
            sheetStack = sheetStack.dropLast(1),
        )
    }

    return if (controlsVisible) {
        MobilePlaybackBackResult.ExitPlayer
    } else {
        MobilePlaybackBackResult.Update(
            controlsVisible = true,
            sheetStack = emptyList(),
        )
    }
}

internal fun supportedMobilePlaybackSettings(
    isLivePlayback: Boolean,
    supportsLiveBufferControl: Boolean,
): List<MobilePlaybackSetting> {
    return buildList {
        add(MobilePlaybackSetting.AUDIO_DELAY)
        add(MobilePlaybackSetting.PLAYBACK_SPEED)
        add(MobilePlaybackSetting.PICTURE_FORMAT)
        if (isLivePlayback) {
            add(MobilePlaybackSetting.LIVE_AUDIO_MODE)
        }
        if (supportsLiveBufferControl) {
            add(MobilePlaybackSetting.LIVE_BUFFER)
        }
    }
}

internal fun resolveLivePlaybackContext(
    url: String,
    title: String,
    channelsState: ChannelsUiState,
): ResolvedLivePlaybackContext {
    val candidates = buildList {
        addAll(channelsState.channels)
        addAll(channelsState.categoryChannels)
        channelsState.categories.forEach { addAll(it.channels) }
        channelsState.selectedChannel?.let { add(EnrichedChannel(channel = it)) }
        channelsState.favorites.forEach { add(EnrichedChannel(channel = it)) }
        channelsState.recentlyViewedChannels.forEach { add(EnrichedChannel(channel = it)) }
    }.distinctBy { candidate ->
        val channel = candidate.channel
        "${channel.playlistId}|${channel.url}|${channel.name}"
    }

    val matched = candidates.firstOrNull { it.channel.url == url }
        ?: candidates.firstOrNull { it.channel.name.equals(title, ignoreCase = true) }

    val channel = matched?.channel
    val selectedChannel = channelsState.selectedChannel
    val programmePair = when {
        matched != null && (matched.currentProgramme != null || matched.nextProgramme != null) -> {
            matched.currentProgramme to matched.nextProgramme
        }

        channel != null && selectedChannel != null && sameChannel(channel, selectedChannel) -> {
            inferProgrammesFromSelection(channelsState.programmes)
        }

        else -> null to null
    }

    val categoryName = channelsState.selectedGroup
        ?: channel?.groupTitle?.let { rawGroup -> CategoryNameCleaner.clean(rawGroup).name }
    val isFavorite = channel?.let { current ->
        channelsState.favorites.any { sameChannel(it, current) }
    } ?: false

    return ResolvedLivePlaybackContext(
        currentChannel = channel,
        currentEnrichedChannel = matched,
        currentProgramme = programmePair.first,
        nextProgramme = programmePair.second,
        currentCategoryName = categoryName,
        isFavorite = isFavorite,
    )
}

private fun inferProgrammesFromSelection(programmes: List<EpgProgramme>): Pair<EpgProgramme?, EpgProgramme?> {
    if (programmes.isEmpty()) return null to null
    val nowMs = System.currentTimeMillis()
    val sorted = programmes.sortedBy { it.startTime }
    val current = sorted.firstOrNull { nowMs in it.startTime until it.endTime }
    val next = when {
        current != null -> sorted.firstOrNull { it.startTime >= current.endTime }
        else -> sorted.firstOrNull { it.startTime > nowMs }
    }
    return current to next
}

private fun sameChannel(left: Channel, right: Channel): Boolean {
    val rightIds = channelIdentityCandidates(right)
    return channelIdentityCandidates(left).any(rightIds::contains)
}
