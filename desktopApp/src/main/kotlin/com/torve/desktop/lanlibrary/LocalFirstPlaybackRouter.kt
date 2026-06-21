package com.torve.desktop.lanlibrary

import com.torve.domain.lanlibrary.PlaybackRoute
import com.torve.domain.lanlibrary.PlaybackRoutePreference
import com.torve.domain.model.DownloadStatus
import com.torve.domain.repository.DownloadRepository
import java.io.File

/**
 * Phase 3 Slice C - playback routing.
 *
 * Given an existing provider URL, ask the desktop's
 * [DownloadRepository] whether the same item is already on disk under
 * the [DownloadFolderAllowlist]. If so, the preferred route is the
 * local file - zero network, instant start, offline-safe. If not, the
 * provider URL stays the preferred route.
 *
 * In a future slice the LAN streaming URL slot is filled in too; for
 * now [LocalFirstPlaybackRouter] is desktop-local only.
 */
class LocalFirstPlaybackRouter(
    private val downloadRepository: DownloadRepository,
    private val allowlist: DownloadFolderAllowlist,
) {

    suspend fun route(
        mediaId: String,
        providerStreamUrl: String?,
    ): PlaybackRoutePreference {
        val localCandidate = resolveLocal(mediaId)
        val provider = providerStreamUrl?.takeIf { it.isNotBlank() }
            ?.let(PlaybackRoute::ProviderStream)
        return PlaybackRoutePreference.of(
            localFile = localCandidate,
            providerStream = provider,
        )
    }

    /**
     * @return a [PlaybackRoute.LocalFile] if and only if a completed
     * download row exists for [mediaId] AND its `filePath` is in the
     * allowlist AND the file actually exists on disk.
     */
    private suspend fun resolveLocal(mediaId: String): PlaybackRoute.LocalFile? {
        val row = runCatching { downloadRepository.getDownloadByMediaId(mediaId) }.getOrNull()
            ?: return null
        if (row.status != DownloadStatus.COMPLETED) return null
        val pathStr = row.filePath ?: return null
        if (!allowlist.isAllowed(pathStr)) return null
        val canonical = runCatching { File(pathStr).canonicalFile }.getOrNull() ?: return null
        return PlaybackRoute.LocalFile(canonical.absolutePath)
    }
}
