package com.torve.data.integrations

import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationAddMediaRequest
import com.torve.domain.integrations.AutomationAdminResult
import com.torve.domain.integrations.AutomationCapability
import com.torve.domain.integrations.AutomationIndexer
import com.torve.domain.integrations.AutomationIndexerCreateRequest
import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationQualityProfile
import com.torve.domain.integrations.AutomationQueueItem
import com.torve.domain.integrations.AutomationQueueRemoval
import com.torve.domain.integrations.AutomationRelease
import com.torve.domain.integrations.AutomationReleaseQuery
import com.torve.domain.integrations.AutomationRootFolder
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.AutomationSubtitleCandidate
import com.torve.domain.integrations.AutomationSubtitleDeleteRequest
import com.torve.domain.integrations.AutomationSubtitleDownloadRequest
import com.torve.domain.integrations.AutomationSubtitleTarget
import com.torve.domain.integrations.TdarrOverview
import com.torve.domain.integrations.TdarrJobActionRequest
import com.torve.domain.integrations.TdarrRunAutomationRequest
import com.torve.domain.integrations.TdarrScanRequest
import com.torve.domain.integrations.TdarrWorkerAction
import com.torve.domain.integrations.TdarrWorkerLimitRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

sealed interface AutomationConnectionResult {
    data class Connected(val version: String?) : AutomationConnectionResult
    data object Unauthorized : AutomationConnectionResult
    data object Unreachable : AutomationConnectionResult
    data object Unsupported : AutomationConnectionResult
}

interface AutomationAdminClient {
    suspend fun testConnection(instance: AutomationInstance, apiKey: String): AutomationConnectionResult

    fun capabilities(instance: AutomationInstance): Set<AutomationCapability> = emptySet()
    suspend fun lookupMedia(instance: AutomationInstance, apiKey: String, query: String): AutomationAdminResult<List<AutomationLibraryItem>> = unsupported()
    suspend fun listLibrary(instance: AutomationInstance, apiKey: String): AutomationAdminResult<List<AutomationLibraryItem>> = unsupported()
    suspend fun qualityProfiles(instance: AutomationInstance, apiKey: String): AutomationAdminResult<List<AutomationQualityProfile>> = unsupported()
    suspend fun rootFolders(instance: AutomationInstance, apiKey: String): AutomationAdminResult<List<AutomationRootFolder>> = unsupported()
    suspend fun addMedia(instance: AutomationInstance, apiKey: String, request: AutomationAddMediaRequest): AutomationAdminResult<AutomationLibraryItem> = unsupported()
    suspend fun interactiveSearch(instance: AutomationInstance, apiKey: String, query: AutomationReleaseQuery): AutomationAdminResult<List<AutomationRelease>> = unsupported()
    suspend fun searchMissingEpisodes(instance: AutomationInstance, apiKey: String, seriesId: Int, monitorRegularSeasons: Boolean = false): AutomationAdminResult<Unit> = unsupported()
    suspend fun grabRelease(instance: AutomationInstance, apiKey: String, release: AutomationRelease): AutomationAdminResult<Unit> = unsupported()
    suspend fun queue(instance: AutomationInstance, apiKey: String): AutomationAdminResult<List<AutomationQueueItem>> = unsupported()
    suspend fun removeQueueItem(instance: AutomationInstance, apiKey: String, queueId: Long, removal: AutomationQueueRemoval): AutomationAdminResult<Unit> = unsupported()
    suspend fun retryQueueItem(instance: AutomationInstance, apiKey: String, queueId: Long): AutomationAdminResult<Unit> = unsupported()
    suspend fun indexers(instance: AutomationInstance, apiKey: String): AutomationAdminResult<List<AutomationIndexer>> = unsupported()
    suspend fun createIndexer(instance: AutomationInstance, apiKey: String, request: AutomationIndexerCreateRequest): AutomationAdminResult<AutomationIndexer> = unsupported()
    suspend fun testIndexer(instance: AutomationInstance, apiKey: String, indexerId: Int): AutomationAdminResult<Unit> = unsupported()
    suspend fun setIndexerEnabled(instance: AutomationInstance, apiKey: String, indexerId: Int, enabled: Boolean): AutomationAdminResult<AutomationIndexer> = unsupported()
    suspend fun deleteIndexer(instance: AutomationInstance, apiKey: String, indexerId: Int): AutomationAdminResult<Unit> = unsupported()
    suspend fun wantedSubtitles(instance: AutomationInstance, apiKey: String): AutomationAdminResult<List<AutomationSubtitleTarget>> = unsupported()
    suspend fun searchSubtitles(instance: AutomationInstance, apiKey: String, target: AutomationSubtitleTarget): AutomationAdminResult<List<AutomationSubtitleCandidate>> = unsupported()
    suspend fun downloadSubtitle(instance: AutomationInstance, apiKey: String, request: AutomationSubtitleDownloadRequest): AutomationAdminResult<Unit> = unsupported()
    suspend fun searchMissingSubtitle(instance: AutomationInstance, apiKey: String, target: AutomationSubtitleTarget, language: String, forced: Boolean = false, hearingImpaired: Boolean = false): AutomationAdminResult<Unit> = unsupported()
    suspend fun deleteSubtitle(instance: AutomationInstance, apiKey: String, request: AutomationSubtitleDeleteRequest): AutomationAdminResult<Unit> = unsupported()
    suspend fun tdarrOverview(instance: AutomationInstance, apiKey: String): AutomationAdminResult<TdarrOverview> = unsupported()
    suspend fun actOnTdarrJob(instance: AutomationInstance, apiKey: String, request: TdarrJobActionRequest): AutomationAdminResult<Unit> = unsupported()
    suspend fun scanTdarrLibrary(instance: AutomationInstance, apiKey: String, request: TdarrScanRequest): AutomationAdminResult<Unit> = unsupported()
    suspend fun runTdarrAutomation(instance: AutomationInstance, apiKey: String, request: TdarrRunAutomationRequest): AutomationAdminResult<Unit> = unsupported()
    suspend fun cancelTdarrWorker(instance: AutomationInstance, apiKey: String, action: TdarrWorkerAction): AutomationAdminResult<Unit> = unsupported()
    suspend fun changeTdarrWorkerLimit(instance: AutomationInstance, apiKey: String, request: TdarrWorkerLimitRequest): AutomationAdminResult<Unit> = unsupported()
}

private fun unsupported(): AutomationAdminResult.Failure = AutomationAdminResult.Failure(
    code = com.torve.domain.integrations.AutomationAdminErrorCode.UNSUPPORTED,
    message = "This operation is not supported by this service",
    retryable = false,
)

internal data class AutomationProbeSpec(
    val path: String,
    val apiKeyHeader: String?,
)

/**
 * Read-only connection probes for the supported automation services.
 *
 * These endpoints deliberately expose no mutation surface. Their API versions
 * are service-specific: Prowlarr does not share Sonarr/Radarr's v3 prefix,
 * while Bazarr and Tdarr use independent status contracts.
 */
class ServarrAdminClient(private val httpClient: HttpClient) : AutomationAdminClient {
    private val administration = ServarrAdministrationApi(httpClient)

    override suspend fun testConnection(
        instance: AutomationInstance,
        apiKey: String,
    ): AutomationConnectionResult {
        val probe = automationProbeSpec(instance.serviceType)
        if (probe.apiKeyHeader != null && apiKey.isBlank()) return AutomationConnectionResult.Unauthorized
        return runCatching {
            val response = httpClient.get("${instance.serverUrl}${probe.path}") {
                probe.apiKeyHeader?.let { headerName -> header(headerName, apiKey.trim()) }
                header(HttpHeaders.Accept, "application/json")
            }
            when {
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                    AutomationConnectionResult.Unauthorized
                response.status.isSuccess() ->
                    AutomationConnectionResult.Connected(
                        runCatching {
                            when (instance.serviceType) {
                                AutomationServiceType.SONARR,
                                AutomationServiceType.RADARR,
                                AutomationServiceType.PROWLARR -> response.body<ServarrSystemStatusDto>().version
                                AutomationServiceType.BAZARR ->
                                    response.body<BazarrSystemStatusDto>().data.bazarrVersion
                                AutomationServiceType.TDARR -> response.body<TdarrSystemStatusDto>().version
                            }
                        }.getOrNull(),
                    )
                else -> AutomationConnectionResult.Unreachable
            }
        }.getOrDefault(AutomationConnectionResult.Unreachable)
    }

    override fun capabilities(instance: AutomationInstance) = administration.capabilities(instance)
    override suspend fun lookupMedia(instance: AutomationInstance, apiKey: String, query: String) = administration.lookupMedia(instance, apiKey, query)
    override suspend fun listLibrary(instance: AutomationInstance, apiKey: String) = administration.listLibrary(instance, apiKey)
    override suspend fun qualityProfiles(instance: AutomationInstance, apiKey: String) = administration.qualityProfiles(instance, apiKey)
    override suspend fun rootFolders(instance: AutomationInstance, apiKey: String) = administration.rootFolders(instance, apiKey)
    override suspend fun addMedia(instance: AutomationInstance, apiKey: String, request: AutomationAddMediaRequest) = administration.addMedia(instance, apiKey, request)
    override suspend fun interactiveSearch(instance: AutomationInstance, apiKey: String, query: AutomationReleaseQuery) = administration.interactiveSearch(instance, apiKey, query)
    override suspend fun searchMissingEpisodes(instance: AutomationInstance, apiKey: String, seriesId: Int, monitorRegularSeasons: Boolean) = administration.searchMissingEpisodes(instance, apiKey, seriesId, monitorRegularSeasons)
    override suspend fun grabRelease(instance: AutomationInstance, apiKey: String, release: AutomationRelease) = administration.grabRelease(instance, apiKey, release)
    override suspend fun queue(instance: AutomationInstance, apiKey: String) = administration.queue(instance, apiKey)
    override suspend fun removeQueueItem(instance: AutomationInstance, apiKey: String, queueId: Long, removal: AutomationQueueRemoval) = administration.removeQueueItem(instance, apiKey, queueId, removal)
    override suspend fun retryQueueItem(instance: AutomationInstance, apiKey: String, queueId: Long) = administration.retryQueueItem(instance, apiKey, queueId)
    override suspend fun indexers(instance: AutomationInstance, apiKey: String) = administration.indexers(instance, apiKey)
    override suspend fun createIndexer(instance: AutomationInstance, apiKey: String, request: AutomationIndexerCreateRequest) = administration.createIndexer(instance, apiKey, request)
    override suspend fun testIndexer(instance: AutomationInstance, apiKey: String, indexerId: Int) = administration.testIndexer(instance, apiKey, indexerId)
    override suspend fun setIndexerEnabled(instance: AutomationInstance, apiKey: String, indexerId: Int, enabled: Boolean) = administration.setIndexerEnabled(instance, apiKey, indexerId, enabled)
    override suspend fun deleteIndexer(instance: AutomationInstance, apiKey: String, indexerId: Int) = administration.deleteIndexer(instance, apiKey, indexerId)
    override suspend fun wantedSubtitles(instance: AutomationInstance, apiKey: String) = administration.wantedSubtitles(instance, apiKey)
    override suspend fun searchSubtitles(instance: AutomationInstance, apiKey: String, target: AutomationSubtitleTarget) = administration.searchSubtitles(instance, apiKey, target)
    override suspend fun downloadSubtitle(instance: AutomationInstance, apiKey: String, request: AutomationSubtitleDownloadRequest) = administration.downloadSubtitle(instance, apiKey, request)
    override suspend fun searchMissingSubtitle(instance: AutomationInstance, apiKey: String, target: AutomationSubtitleTarget, language: String, forced: Boolean, hearingImpaired: Boolean) = administration.searchMissingSubtitle(instance, apiKey, target, language, forced, hearingImpaired)
    override suspend fun deleteSubtitle(instance: AutomationInstance, apiKey: String, request: AutomationSubtitleDeleteRequest) = administration.deleteSubtitle(instance, apiKey, request)
    override suspend fun tdarrOverview(instance: AutomationInstance, apiKey: String) = administration.tdarrOverview(instance, apiKey)
    override suspend fun actOnTdarrJob(instance: AutomationInstance, apiKey: String, request: TdarrJobActionRequest) = administration.actOnTdarrJob(instance, apiKey, request)
    override suspend fun scanTdarrLibrary(instance: AutomationInstance, apiKey: String, request: TdarrScanRequest) = administration.scanTdarrLibrary(instance, apiKey, request)
    override suspend fun runTdarrAutomation(instance: AutomationInstance, apiKey: String, request: TdarrRunAutomationRequest) = administration.runTdarrAutomation(instance, apiKey, request)
    override suspend fun cancelTdarrWorker(instance: AutomationInstance, apiKey: String, action: TdarrWorkerAction) = administration.cancelTdarrWorker(instance, apiKey, action)
    override suspend fun changeTdarrWorkerLimit(instance: AutomationInstance, apiKey: String, request: TdarrWorkerLimitRequest) = administration.changeTdarrWorkerLimit(instance, apiKey, request)
}

internal fun automationProbeSpec(type: AutomationServiceType): AutomationProbeSpec = when (type) {
    AutomationServiceType.SONARR,
    AutomationServiceType.RADARR -> AutomationProbeSpec("/api/v3/system/status", "X-Api-Key")
    AutomationServiceType.PROWLARR -> AutomationProbeSpec("/api/v1/system/status", "X-Api-Key")
    AutomationServiceType.BAZARR -> AutomationProbeSpec("/api/system/status", "X-API-KEY")
    AutomationServiceType.TDARR -> AutomationProbeSpec("/api/v2/status", null)
}

@Serializable
private data class ServarrSystemStatusDto(val version: String? = null)

@Serializable
private data class BazarrSystemStatusDto(val data: BazarrStatusData = BazarrStatusData())

@Serializable
private data class BazarrStatusData(
    @kotlinx.serialization.SerialName("bazarr_version")
    val bazarrVersion: String? = null,
)

@Serializable
private data class TdarrSystemStatusDto(val version: String? = null)
