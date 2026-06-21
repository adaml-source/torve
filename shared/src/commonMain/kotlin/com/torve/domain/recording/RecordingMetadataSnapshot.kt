package com.torve.domain.recording

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme

data class RecordingMetadataSnapshot(
    val programmeTitle: String,
    val programmeDescription: String?,
    val epgProgrammeTitle: String?,
    val epgProgrammeSubtitle: String?,
    val epgProgrammeCategory: String?,
    val epgProgrammeIconUrl: String?,
    val epgChannelId: String?,
    val sourceLabel: String?,
    val recordingKind: RecordingKind,
    val epgMatchStatus: RecordingEpgMatchStatus,
)

object EpgRecordingMetadataResolver {

    fun scheduled(
        channel: Channel,
        programme: EpgProgramme,
        sourceLabel: String? = channel.groupTitle,
    ): RecordingMetadataSnapshot = RecordingMetadataSnapshot(
        programmeTitle = programme.title.ifBlank { channel.name },
        programmeDescription = programme.description,
        epgProgrammeTitle = programme.title.takeIf { it.isNotBlank() },
        epgProgrammeSubtitle = programme.subTitle?.takeIf { it.isNotBlank() },
        epgProgrammeCategory = programme.category?.takeIf { it.isNotBlank() },
        epgProgrammeIconUrl = programme.iconUrl?.takeIf { it.isNotBlank() } ?: channel.tvgLogo?.takeIf { it.isNotBlank() },
        epgChannelId = programme.channelId.takeIf { it.isNotBlank() } ?: channel.tvgId?.takeIf { it.isNotBlank() },
        sourceLabel = sourceLabel?.takeIf { it.isNotBlank() },
        recordingKind = RecordingKind.SCHEDULED_EPG,
        epgMatchStatus = RecordingEpgMatchStatus.MATCHED,
    )

    fun live(
        channel: Channel,
        programmes: List<EpgProgramme>,
        nowMs: Long,
        sourceLabel: String? = channel.groupTitle,
    ): RecordingMetadataSnapshot {
        val current = programmes.firstOrNull { it.startTime <= nowMs && it.endTime > nowMs }
        return if (current != null) {
            RecordingMetadataSnapshot(
                programmeTitle = current.title.ifBlank { channel.name },
                programmeDescription = current.description,
                epgProgrammeTitle = current.title.takeIf { it.isNotBlank() },
                epgProgrammeSubtitle = current.subTitle?.takeIf { it.isNotBlank() },
                epgProgrammeCategory = current.category?.takeIf { it.isNotBlank() },
                epgProgrammeIconUrl = current.iconUrl?.takeIf { it.isNotBlank() } ?: channel.tvgLogo?.takeIf { it.isNotBlank() },
                epgChannelId = current.channelId.takeIf { it.isNotBlank() } ?: channel.tvgId?.takeIf { it.isNotBlank() },
                sourceLabel = sourceLabel?.takeIf { it.isNotBlank() },
                recordingKind = RecordingKind.LIVE,
                epgMatchStatus = RecordingEpgMatchStatus.MATCHED,
            )
        } else {
            RecordingMetadataSnapshot(
                programmeTitle = channel.name,
                programmeDescription = null,
                epgProgrammeTitle = null,
                epgProgrammeSubtitle = null,
                epgProgrammeCategory = null,
                epgProgrammeIconUrl = channel.tvgLogo?.takeIf { it.isNotBlank() },
                epgChannelId = channel.tvgId?.takeIf { it.isNotBlank() },
                sourceLabel = sourceLabel?.takeIf { it.isNotBlank() },
                recordingKind = RecordingKind.LIVE,
                epgMatchStatus = RecordingEpgMatchStatus.NO_MATCH_AT_START,
            )
        }
    }
}
