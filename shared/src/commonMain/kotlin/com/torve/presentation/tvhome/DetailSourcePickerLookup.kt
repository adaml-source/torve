package com.torve.presentation.tvhome

import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus

/**
 * Pure helper for finding the local-file side of a source picker.
 *
 * Detail screens already know the candidate media ids they use for
 * downloads (`tmdbId`, internal id). This helper keeps the movie and
 * episode matching rules identical across mobile and TV without
 * coupling shared code to a repository implementation.
 */
object DetailSourcePickerLookup {

    fun completedLocalFilePath(
        downloads: List<Download>,
        mediaIds: Set<String>,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): String? {
        if (mediaIds.isEmpty()) return null
        return downloads
            .asSequence()
            .filter { it.status == DownloadStatus.COMPLETED }
            .filter { it.mediaId in mediaIds }
            .filter { it.filePath?.isNotBlank() == true }
            .filter { row ->
                if (seasonNumber == null && episodeNumber == null) {
                    row.seasonNumber == null && row.episodeNumber == null
                } else {
                    row.seasonNumber == seasonNumber && row.episodeNumber == episodeNumber
                }
            }
            .sortedByDescending { it.completedAt ?: it.createdAt }
            .firstOrNull()
            ?.filePath
    }
}
