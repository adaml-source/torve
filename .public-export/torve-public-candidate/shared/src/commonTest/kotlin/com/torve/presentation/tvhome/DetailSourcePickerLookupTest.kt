package com.torve.presentation.tvhome

import com.torve.domain.model.Download
import com.torve.domain.model.DownloadStatus
import com.torve.domain.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailSourcePickerLookupTest {

    @Test
    fun `movie lookup returns completed movie file only`() {
        val path = DetailSourcePickerLookup.completedLocalFilePath(
            downloads = listOf(
                download(mediaId = "1", status = DownloadStatus.DOWNLOADING, path = "/tmp/down.mkv"),
                download(mediaId = "1", status = DownloadStatus.COMPLETED, path = "/tmp/movie.mkv"),
                download(mediaId = "1", status = DownloadStatus.COMPLETED, path = "/tmp/episode.mkv", season = 1, episode = 1),
            ),
            mediaIds = setOf("1"),
        )

        assertEquals("/tmp/movie.mkv", path)
    }

    @Test
    fun `episode lookup requires matching season and episode`() {
        val path = DetailSourcePickerLookup.completedLocalFilePath(
            downloads = listOf(
                download(mediaId = "tv", status = DownloadStatus.COMPLETED, path = "/tmp/s1e1.mkv", season = 1, episode = 1),
                download(mediaId = "tv", status = DownloadStatus.COMPLETED, path = "/tmp/s1e2.mkv", season = 1, episode = 2),
            ),
            mediaIds = setOf("tv"),
            seasonNumber = 1,
            episodeNumber = 2,
        )

        assertEquals("/tmp/s1e2.mkv", path)
    }

    @Test
    fun `newest completed duplicate wins`() {
        val path = DetailSourcePickerLookup.completedLocalFilePath(
            downloads = listOf(
                download(mediaId = "1", status = DownloadStatus.COMPLETED, path = "/tmp/old.mkv", completedAt = 100),
                download(mediaId = "1", status = DownloadStatus.COMPLETED, path = "/tmp/new.mkv", completedAt = 200),
            ),
            mediaIds = setOf("1"),
        )

        assertEquals("/tmp/new.mkv", path)
    }

    @Test
    fun `missing file or media id returns null`() {
        val path = DetailSourcePickerLookup.completedLocalFilePath(
            downloads = listOf(download(mediaId = "2", status = DownloadStatus.COMPLETED, path = " ")),
            mediaIds = setOf("1"),
        )

        assertNull(path)
    }

    private fun download(
        mediaId: String,
        status: DownloadStatus,
        path: String?,
        season: Int? = null,
        episode: Int? = null,
        completedAt: Long? = 10,
    ): Download = Download(
        id = "$mediaId:${season ?: 0}:${episode ?: 0}:${path ?: ""}",
        mediaId = mediaId,
        mediaType = if (season == null) MediaType.MOVIE else MediaType.SERIES,
        title = "Title",
        streamUrl = "https://source",
        filePath = path,
        status = status,
        seasonNumber = season,
        episodeNumber = episode,
        createdAt = 1,
        completedAt = completedAt,
    )
}
