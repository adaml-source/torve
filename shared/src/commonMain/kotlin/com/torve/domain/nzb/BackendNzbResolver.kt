package com.torve.domain.nzb

import com.torve.data.usenet.UsenetRepository
import com.torve.data.usenet.model.UsenetResolveResponseDto
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * [NzbResolver] backed by the Torve backend resolver + NzbDAV integration.
 *
 * Flow when the user clicks Play on a raw Newznab result:
 *  1. POST /resolver/usenet/resolve-nzb with the NZB URL. The server derives
 *     a stable dedup key from sha256(nzb_url) so double-clicks on the same
 *     row reuse the same backend job rather than spawning duplicates.
 *  2. Response is "ready" (cache hit — stream URL returned immediately) or
 *     "warming" (download in progress — poll the job endpoint).
 *  3. If warming: poll until "ready", then re-call resolve-nzb to pick up
 *     the freshly-cached handoff URL.
 *  4. Return the opaque Torve handoff URL for the player.
 *
 * Requires NzbDAV to be registered in the user's Torve account. The backend
 * returns HTTP 422 with error_code="nzbdav_not_configured" when it is not,
 * which surfaces as [Result.failure] with a clear message.
 */
class BackendNzbResolver(
    private val repository: UsenetRepository,
    /**
     * Set by the call site from the cached NzbDAV integration status.
     * True when status is Loading or Connected (benefit of the doubt while
     * the backend confirms). False when NotConfigured or ConnectionFailed —
     * the browse page shows the setup prompt instead of a runtime error.
     */
    private val configured: Boolean = true,
    private val pollIntervalMs: Long = 3_000L,
    private val timeoutMs: Long = 5 * 60_000L,
) : NzbResolver {

    override val isConfigured: Boolean get() = configured

    override suspend fun resolve(
        nzbUrl: String,
        onStatus: (String) -> Unit,
    ): Result<ResolvedNzb> = runCatching {
        onStatus("Sending to backend resolver…")
        var response = repository.resolveBarNzb(nzbUrl = nzbUrl, title = "")

        if (response.state == "warming") {
            response = awaitWarming(response, nzbUrl, onStatus)
        }

        val stream = response.stream ?: error(
            when (response.state) {
                "failed" -> "Backend could not resolve this NZB" +
                    response.failureCode?.let { " ($it)" }.orEmpty()
                else -> "Backend did not return a stream URL (state=${response.state})"
            },
        )
        ResolvedNzb(streamUrl = stream.url, fileName = "", sizeBytes = 0L)
    }

    private suspend fun awaitWarming(
        initial: UsenetResolveResponseDto,
        nzbUrl: String,
        onStatus: (String) -> Unit,
    ): UsenetResolveResponseDto {
        val jobId = initial.jobId ?: error("Backend returned warming state without a job id")
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        onStatus("Backend preparing download…")
        poll@ while (Clock.System.now().toEpochMilliseconds() < deadline) {
            delay(pollIntervalMs)
            val job = repository.getUsenetJobStatus(jobId)
            when (job.state) {
                "ready" -> {
                    onStatus("Resolving stream link…")
                    // Re-call resolve-nzb — backend returns the cached handoff URL.
                    return repository.resolveBarNzb(nzbUrl = nzbUrl, title = "")
                }
                "failed" -> error(
                    "Backend could not complete this download" +
                        job.failureCode?.let { " ($it)" }.orEmpty(),
                )
                else -> onStatus("Backend preparing · ${job.state}…")
            }
        }
        error("Backend did not finish within 5 minutes")
    }
}
