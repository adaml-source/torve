package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadMediaType {
    MOVIE,
    EPISODE,
}

@Serializable
data class DownloadedItem(
    val id: String,
    val mediaId: String,
    val title: String,
    val type: DownloadMediaType,
    // Episode info (null for movies)
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    // File info
    val filePath: String? = null,
    val fileSizeBytes: Long = 0,
    val mimeType: String? = null,
    // Quality info (parsed from title or stored metadata)
    val resolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: String? = null,
    val hdrType: String? = null,
    // Metadata
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val genres: List<String>? = null,
    val runtime: Int? = null,
    val contentRating: String? = null,
    val imdbRating: Float? = null,
    // Download state
    val downloadedAt: Long = 0,
    val downloadSource: String? = null,
    // Watch state
    val watchProgress: Float = 0f,
    val isWatched: Boolean = false,
    val lastWatchedAt: Long? = null,
    val watchCount: Int = 0,
)

enum class DownloadGroupType { MOVIE, SHOW }

data class DownloadGroup(
    val mediaId: String,
    val title: String,
    val type: DownloadGroupType,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val genres: List<String>?,
    val imdbRating: Float?,
    val contentRating: String?,
    val totalSizeBytes: Long,
    val itemCount: Int,
    val totalRuntime: Int?,
    val seasons: List<DownloadSeason>? = null,
    val movie: DownloadedItem? = null,
    val watchedCount: Int = 0,
    val totalCount: Int = 1,
    val overallProgress: Float = 0f,
    val latestDownloadAt: Long = 0,
    val latestWatchedAt: Long? = null,
)

data class DownloadSeason(
    val seasonNumber: Int,
    val episodes: List<DownloadedItem>,
    val totalSizeBytes: Long,
    val watchedCount: Int,
)

data class CatalogueSection(
    val title: String,
    val items: List<DownloadGroup>,
)

data class CatalogueState(
    val isEmpty: Boolean = true,
    val specialSections: List<CatalogueSection> = emptyList(),
    val sections: List<CatalogueSection> = emptyList(),
    val totalSizeBytes: Long = 0,
    val movieCount: Int = 0,
    val showCount: Int = 0,
    val episodeCount: Int = 0,
    val totalItemCount: Int = 0,
    val availableGenres: List<String> = emptyList(),
    val availableQualities: List<String> = emptyList(),
)

// ── Bridge: Download → DownloadedItem ──

fun Download.toDownloadedItem(
    watchProgress: WatchProgress? = null,
): DownloadedItem {
    val parsedResolution = parseResolutionFromTitle(title)
    val parsedCodec = parseCodecFromTitle(title)
    val parsedHdr = parseHdrFromTitle(title)
    val parsedAudio = parseAudioCodecFromTitle(title)
    val parsedChannels = parseAudioChannelsFromTitle(title)
    val parsedYear = parseYearFromTitle(title)
    val progressPercent = watchProgress?.progressPercent ?: 0f
    val isMovie = mediaType == MediaType.MOVIE

    return DownloadedItem(
        id = id,
        mediaId = mediaId,
        title = title,
        type = if (isMovie) DownloadMediaType.MOVIE else DownloadMediaType.EPISODE,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        filePath = filePath,
        fileSizeBytes = fileSizeBytes ?: 0,
        resolution = parsedResolution,
        videoCodec = parsedCodec,
        audioCodec = parsedAudio,
        audioChannels = parsedChannels,
        hdrType = parsedHdr,
        posterUrl = posterUrl,
        year = parsedYear,
        downloadedAt = completedAt ?: createdAt,
        watchProgress = progressPercent,
        isWatched = progressPercent >= 0.9f,
        lastWatchedAt = watchProgress?.updatedAt,
    )
}

// ── Title Parsing Helpers ──

private val resolutionRegex = Regex(
    "\\b(2160p|4K|UHD|1080p|720p|480p|360p)\\b",
    RegexOption.IGNORE_CASE,
)

private val codecRegex = Regex(
    "\\b(HEVC|H\\.?265|x265|H\\.?264|x264|AV1|VP9|MPEG-?4)\\b",
    RegexOption.IGNORE_CASE,
)

private val hdrRegex = Regex(
    "\\b(HDR10\\+|HDR10|HDR|DV|Dolby\\.?Vision|HLG)\\b",
    RegexOption.IGNORE_CASE,
)

private val audioCodecRegex = Regex(
    "\\b(EAC3|E-AC-3|AC3|AAC|TrueHD|Atmos|DTS-HD|DTS|FLAC|Opus|MP3)\\b",
    RegexOption.IGNORE_CASE,
)

private val audioChannelsRegex = Regex(
    "\\b(7\\.1|5\\.1|2\\.0|2\\.1|Stereo|Mono)\\b",
    RegexOption.IGNORE_CASE,
)

private val yearRegex = Regex("\\b(19\\d{2}|20\\d{2})\\b")

private fun parseResolutionFromTitle(title: String): String? {
    val match = resolutionRegex.find(title) ?: return null
    return when (match.value.lowercase()) {
        "2160p", "4k", "uhd" -> "4K"
        "1080p" -> "1080p"
        "720p" -> "720p"
        "480p" -> "480p"
        "360p" -> "360p"
        else -> match.value
    }
}

private fun parseCodecFromTitle(title: String): String? {
    val match = codecRegex.find(title) ?: return null
    return when {
        match.value.contains("265", ignoreCase = true) ||
            match.value.equals("HEVC", ignoreCase = true) -> "H.265"
        match.value.contains("264", ignoreCase = true) -> "H.264"
        match.value.equals("AV1", ignoreCase = true) -> "AV1"
        match.value.equals("VP9", ignoreCase = true) -> "VP9"
        else -> match.value.uppercase()
    }
}

private fun parseHdrFromTitle(title: String): String? {
    val match = hdrRegex.find(title) ?: return null
    return when {
        match.value.contains("HDR10+", ignoreCase = true) -> "HDR10+"
        match.value.contains("HDR10", ignoreCase = true) -> "HDR10"
        match.value.equals("HDR", ignoreCase = true) -> "HDR"
        match.value.contains("DV", ignoreCase = true) ||
            match.value.contains("Dolby", ignoreCase = true) -> "DV"
        match.value.equals("HLG", ignoreCase = true) -> "HLG"
        else -> match.value
    }
}

private fun parseAudioCodecFromTitle(title: String): String? {
    val match = audioCodecRegex.find(title) ?: return null
    return when {
        match.value.contains("EAC3", ignoreCase = true) ||
            match.value.contains("E-AC-3", ignoreCase = true) -> "EAC3"
        match.value.equals("AC3", ignoreCase = true) -> "AC3"
        match.value.equals("AAC", ignoreCase = true) -> "AAC"
        match.value.contains("TrueHD", ignoreCase = true) -> "TrueHD"
        match.value.equals("Atmos", ignoreCase = true) -> "Atmos"
        match.value.contains("DTS-HD", ignoreCase = true) -> "DTS-HD"
        match.value.equals("DTS", ignoreCase = true) -> "DTS"
        match.value.equals("FLAC", ignoreCase = true) -> "FLAC"
        match.value.equals("Opus", ignoreCase = true) -> "Opus"
        else -> match.value.uppercase()
    }
}

private fun parseAudioChannelsFromTitle(title: String): String? {
    val match = audioChannelsRegex.find(title) ?: return null
    return when (match.value.lowercase()) {
        "stereo" -> "2.0"
        "mono" -> "1.0"
        else -> match.value
    }
}

private fun parseYearFromTitle(title: String): Int? {
    return yearRegex.find(title)?.value?.toIntOrNull()
}
