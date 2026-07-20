package com.torve.domain.nzb

import com.torve.data.usenet.TorBoxUsenetClient

/**
 * [NzbResolver] backed by the TorBox cloud-Usenet API.
 *
 * Configured when the user has set download_client = "torbox" in Panda
 * with a usable (non-blank, non-redacted) API key. If [apiKey] is blank,
 * [isConfigured] is false and the browse-page Play button stays disabled.
 */
class TorBoxNzbResolver(
    private val client: TorBoxUsenetClient,
    private val apiKey: String,
) : NzbResolver {

    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    override fun isAuthError(message: String?): Boolean = client.isAuthError(message)

    override suspend fun resolve(
        nzbUrl: String,
        onStatus: (String) -> Unit,
    ): Result<ResolvedNzb> = client.resolve(nzbUrl, apiKey, onStatus).map {
        ResolvedNzb(streamUrl = it.streamUrl, fileName = it.fileName, sizeBytes = it.sizeBytes)
    }
}
