package com.torve.domain.nzb

/**
 * Abstracts NZB-to-stream resolution for the browse surfaces (Adult, Sports).
 *
 * Two implementations:
 *  - [TorBoxNzbResolver]   — cloud Usenet via TorBox. Configured when the
 *    user has set download_client = "torbox" in Panda with a valid key.
 *  - [BackendNzbResolver]  — Torve backend resolver + NzbDAV. Configured
 *    when Usenet is enabled in Panda and an NzbDAV integration is registered.
 *
 * The browse pages call [resolve] and never reference TorBox or the backend
 * directly, so swapping the implementation requires no UI change.
 */
interface NzbResolver {
    /** False when credentials are missing — the Play button is disabled. */
    val isConfigured: Boolean

    /**
     * True if [message] looks like an authentication failure the user can
     * fix by rotating their credentials. Drives the "Reconfigure" CTA.
     */
    fun isAuthError(message: String?): Boolean = false

    /**
     * Resolve [nzbUrl] to a playable stream URL. [onStatus] receives short
     * human-readable progress strings shown in the row. Never throws — all
     * failures surface as [Result.failure] with a user-readable message.
     */
    suspend fun resolve(
        nzbUrl: String,
        onStatus: (String) -> Unit,
    ): Result<ResolvedNzb>
}

data class ResolvedNzb(
    val streamUrl: String,
    val fileName: String,
    val sizeBytes: Long,
)
