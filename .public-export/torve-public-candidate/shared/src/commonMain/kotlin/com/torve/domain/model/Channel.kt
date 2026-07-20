package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChannelContentType {
    LIVE, VOD_MOVIE, VOD_SERIES, UNKNOWN
}

@Serializable
data class Channel(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val groupTitle: String? = null,
    val tvgLanguage: String? = null,
    val tvgCountry: String? = null,
    val tvgShift: Int? = null,
    val channelNumber: Int? = null,
    val duration: Int = -1,
    val catchupType: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val vlcOptions: List<String> = emptyList(),
    val kodiProps: Map<String, String> = emptyMap(),
    val isFavorite: Boolean = false,
    val playlistId: String = "",
    val contentType: ChannelContentType = ChannelContentType.UNKNOWN,
)

enum class PlaylistType {
    M3U, XTREAM;

    companion object {
        fun fromString(s: String): PlaylistType = when (s.lowercase()) {
            "xtream" -> XTREAM
            else -> M3U
        }
    }
}

@Serializable
data class ChannelPlaylist(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String? = null,
    val channelCount: Int = 0,
    val lastUpdated: Long? = null,
    val type: PlaylistType = PlaylistType.M3U,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class M3uPlaylist(
    val epgUrl: String? = null,
    val refreshSeconds: Int? = null,
    val channels: List<Channel>,
    val error: String? = null,
)

@Serializable
data class EpgChannel(
    val id: String,
    val displayName: String,
    val iconUrl: String? = null,
)

@Serializable
data class EpgProgramme(
    val channelId: String,
    val startTime: Long,
    val endTime: Long,
    val title: String,
    val subTitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
)

@Serializable
data class EpgData(
    val channels: Map<String, EpgChannel> = emptyMap(),
    val programmes: List<EpgProgramme> = emptyList(),
    val programmesByChannelKey: Map<String, List<EpgProgramme>> = emptyMap(),
    val generationId: Long? = null,
)

data class EnrichedChannel(
    val channel: Channel,
    val currentProgramme: EpgProgramme? = null,
    val nextProgramme: EpgProgramme? = null,
)

data class ChannelCategory(
    val name: String,
    val channelCount: Int,
    val isExpanded: Boolean = false,
    val channels: List<EnrichedChannel> = emptyList(),
    val qualityTags: Set<String> = emptySet(),
    val countryCode: String? = null,
)
