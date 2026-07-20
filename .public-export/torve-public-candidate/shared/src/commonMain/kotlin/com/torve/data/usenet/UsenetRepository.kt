package com.torve.data.usenet

import com.torve.data.usenet.model.NzbdavStatusResponseDto
import com.torve.data.usenet.model.NzbdavTestResponseDto
import com.torve.data.usenet.model.ResolvedStreamDto
import com.torve.data.usenet.model.UsenetCancelResponseDto
import com.torve.data.usenet.model.UsenetCandidateDto
import com.torve.data.usenet.model.UsenetJobStatusResponseDto
import com.torve.data.usenet.model.UsenetResolveResponseDto
import com.torve.data.usenet.model.UsenetWarmResponseDto
import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.integrations.IntegrationStorageMode

/**
 * Single app-side entry point for every NzbDAV + Usenet-resolver call.
 *
 * Existence rationale: callers (settings VM, warm coordinator, resolve
 * coordinator) should depend on a thin domain-flavored surface rather than
 * the Ktor wrapper directly — easier to fake in tests and easier to swap if
 * the backend contract evolves.
 *
 * Scope for Prompt 1: pure pass-through + credential local-persist. The
 * simplified Ready / Preparing / Unavailable mapping and the warm/resolve
 * orchestration coordinators land in later prompts.
 */
interface UsenetRepository {

    // ── Integration config ──────────────────────────────────────────────

    /** Ask the backend to verify a candidate NzbDAV credential pair. */
    suspend fun testNzbdavIntegration(baseUrl: String, apiKey: String): NzbdavTestResponseDto

    /**
     * Persist the credential pair on the backend and mirror it into the
     * local [IntegrationSecretStore] so later sessions can pre-fill the
     * settings form. The backend owns the authoritative copy and returns
     * the refreshed status payload, which the Settings VM can render
     * directly without a follow-up GET.
     */
    suspend fun saveNzbdavIntegration(
        baseUrl: String,
        apiKey: String,
        enabled: Boolean = true,
        storageMode: IntegrationStorageMode = IntegrationStorageMode.ACCOUNT,
    ): NzbdavStatusResponseDto

    /** Current backend-owned integration status, for the Settings card. */
    suspend fun getNzbdavStatus(): NzbdavStatusResponseDto

    /**
     * Remove the integration on the backend and clear the local mirror.
     * Idempotent — a 404 from the backend is treated as success.
     */
    suspend fun deleteNzbdavIntegration()

    // ── Resolver ────────────────────────────────────────────────────────

    /**
     * Ask the backend to start warming the supplied candidates for
     * [contentId]. Callers project their domain payloads to the wire DTO
     * via [com.torve.data.usenet.UsenetCandidateMapping.toDto] before
     * calling. Pass the small top-N subset decided by the UI (1–2 on
     * detail open, 3–5 on source-sheet open).
     */
    suspend fun warmUsenetCandidates(
        contentId: String,
        candidates: List<UsenetCandidateDto>,
        topN: Int? = null,
    ): UsenetWarmResponseDto

    /** Kick off (or short-circuit, if already cached) a resolve for one row. */
    suspend fun resolveUsenetCandidate(
        contentId: String,
        candidate: UsenetCandidateDto,
    ): UsenetResolveResponseDto

    /**
     * Resolve a bare NZB URL for the browse surfaces (Adult, Sports).
     * Calls `POST /resolver/usenet/resolve-nzb`. The backend derives a
     * stable dedup key from the URL so double-clicks don't create duplicate
     * jobs. Returns the same shape as [resolveUsenetCandidate].
     */
    suspend fun resolveBarNzb(nzbUrl: String, title: String): UsenetResolveResponseDto

    /** Poll a warming job. Callers are responsible for cadence + lifecycle. */
    suspend fun getUsenetJobStatus(jobId: String): UsenetJobStatusResponseDto

    /**
     * Best-effort cleanup when the user leaves the flow. Scopes:
     *  - [candidateId] cancels the targeted candidate the user just abandoned.
     *  - [contentId] cancels the whole title's outstanding work.
     * `job_ids` is no longer accepted by the backend and is removed from
     * the surface. Non-fatal on failure — callers swallow errors.
     */
    suspend fun cancelUsenetWarmJobs(
        contentId: String? = null,
        candidateId: String? = null,
        userSession: String? = null,
    ): UsenetCancelResponseDto

    /**
     * Opaque-token handoff re-fetch. Only used on the early-playback-failure
     * retry path (Prompt 5); normal flow consumes [ResolvedStreamDto.url]
     * directly without going through this.
     */
    suspend fun getUsenetHandoff(token: String): ResolvedStreamDto
}

class UsenetRepositoryImpl(
    private val api: UsenetApi,
    private val secretStore: IntegrationSecretStore,
) : UsenetRepository {

    override suspend fun testNzbdavIntegration(
        baseUrl: String,
        apiKey: String,
    ): NzbdavTestResponseDto = api.testIntegration(baseUrl, apiKey)

    override suspend fun saveNzbdavIntegration(
        baseUrl: String,
        apiKey: String,
        enabled: Boolean,
        storageMode: IntegrationStorageMode,
    ): NzbdavStatusResponseDto {
        val status = api.saveIntegration(baseUrl = baseUrl, apiKey = apiKey, enabled = enabled)
        // Local mirror — lets the settings screen pre-fill on next open
        // without a blocking GET /status. Backend remains authoritative.
        secretStore.setStorageMode(IntegrationSecretKey.NZBDAV_BASE_URL, storageMode)
        secretStore.setStorageMode(IntegrationSecretKey.NZBDAV_API_KEY, storageMode)
        secretStore.put(IntegrationSecretKey.NZBDAV_BASE_URL, baseUrl)
        secretStore.put(IntegrationSecretKey.NZBDAV_API_KEY, apiKey)
        return status
    }

    override suspend fun getNzbdavStatus(): NzbdavStatusResponseDto = api.getStatus()

    override suspend fun deleteNzbdavIntegration() {
        api.deleteIntegration()
        secretStore.remove(IntegrationSecretKey.NZBDAV_BASE_URL)
        secretStore.remove(IntegrationSecretKey.NZBDAV_API_KEY)
    }

    override suspend fun warmUsenetCandidates(
        contentId: String,
        candidates: List<UsenetCandidateDto>,
        topN: Int?,
    ): UsenetWarmResponseDto = api.warm(contentId = contentId, candidates = candidates, topN = topN)

    override suspend fun resolveUsenetCandidate(
        contentId: String,
        candidate: UsenetCandidateDto,
    ): UsenetResolveResponseDto = api.resolve(contentId = contentId, candidate = candidate)

    override suspend fun resolveBarNzb(
        nzbUrl: String,
        title: String,
    ): UsenetResolveResponseDto = api.resolveBarNzb(nzbUrl = nzbUrl, title = title)

    override suspend fun getUsenetJobStatus(jobId: String): UsenetJobStatusResponseDto =
        api.getJobStatus(jobId)

    override suspend fun cancelUsenetWarmJobs(
        contentId: String?,
        candidateId: String?,
        userSession: String?,
    ): UsenetCancelResponseDto = api.cancel(
        contentId = contentId,
        candidateId = candidateId,
        userSession = userSession,
    )

    override suspend fun getUsenetHandoff(token: String): ResolvedStreamDto =
        api.getHandoff(token)
}
