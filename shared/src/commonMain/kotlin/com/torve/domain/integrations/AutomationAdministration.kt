package com.torve.domain.integrations

/** Administrative capabilities exposed by an automation service instance. */
enum class AutomationCapability {
    LIBRARY_LOOKUP,
    LIBRARY_ADD,
    RELEASE_SEARCH,
    RELEASE_GRAB,
    QUEUE_READ,
    QUEUE_CONTROL,
    INDEXER_READ,
    INDEXER_CONTROL,
    INDEXER_CREATE,
    SUBTITLE_WANTED,
    SUBTITLE_SEARCH,
    SUBTITLE_CONTROL,
    TDARR_LIBRARIES,
    TDARR_NODES,
    TDARR_JOBS,
    TDARR_CONTROL,
}

enum class AutomationAdminErrorCode {
    INVALID_REQUEST,
    UNAUTHORIZED,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    UNREACHABLE,
    UNSUPPORTED,
    SERVER_ERROR,
    INVALID_RESPONSE,
}

/**
 * Typed result used by every administration action. Messages are deliberately
 * neutral and must never contain API keys, response bodies, or release URLs.
 */
sealed interface AutomationAdminResult<out T> {
    data class Success<T>(val value: T) : AutomationAdminResult<T>

    data class Failure(
        val code: AutomationAdminErrorCode,
        val message: String,
        val retryable: Boolean,
    ) : AutomationAdminResult<Nothing>
}

enum class AutomationMediaKind { SERIES, MOVIE, EPISODE }

data class AutomationLibraryItem(
    val id: Int,
    val kind: AutomationMediaKind,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val externalId: Int? = null,
    val monitored: Boolean = false,
    val hasFile: Boolean = false,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class AutomationQualityProfile(val id: Int, val name: String)

data class AutomationRootFolder(
    val id: Int,
    val path: String,
    val freeSpaceBytes: Long? = null,
)

data class AutomationAddMediaRequest(
    val item: AutomationLibraryItem,
    val qualityProfileId: Int,
    val rootFolderPath: String,
    val monitor: Boolean = true,
    val searchOnAdd: Boolean = true,
)

data class AutomationReleaseQuery(
    val mediaId: Int,
    val episodeId: Int? = null,
)

data class AutomationRelease(
    val guid: String,
    val indexerId: Int,
    val title: String,
    val indexer: String? = null,
    val protocol: String? = null,
    val quality: String? = null,
    val sizeBytes: Long? = null,
    val ageHours: Double? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val approved: Boolean = false,
    val rejections: List<String> = emptyList(),
)

data class AutomationQueueItem(
    val id: Long,
    val title: String,
    val status: String,
    val trackedStatus: String? = null,
    val progressPercent: Double? = null,
    val sizeBytes: Long? = null,
    val remainingBytes: Long? = null,
    val timeLeft: String? = null,
    val errorMessage: String? = null,
)

data class AutomationQueueRemoval(
    val removeFromDownloadClient: Boolean = true,
    val blocklistRelease: Boolean = false,
    val searchAgain: Boolean = false,
)

data class AutomationIndexer(
    val id: Int,
    val name: String,
    val implementation: String? = null,
    val protocol: String? = null,
    val enabled: Boolean,
    val interactiveSearchEnabled: Boolean? = null,
    val automaticSearchEnabled: Boolean? = null,
    val rssEnabled: Boolean? = null,
    val priority: Int? = null,
    val status: String? = null,
)

enum class AutomationIndexerProtocol { TORRENT, USENET }

/** Credentials in this request are transient and must never be retained in UI state. */
data class AutomationIndexerCreateRequest(
    val name: String,
    val protocol: AutomationIndexerProtocol,
    val baseUrl: String,
    val apiPath: String = "/api",
    val apiKey: String = "",
    val additionalParameters: String = "",
    val minimumSeeders: Int = 1,
    val priority: Int = 25,
)

enum class AutomationSubtitleKind { EPISODE, MOVIE }

data class AutomationSubtitleTarget(
    val kind: AutomationSubtitleKind,
    val mediaId: Int,
    val seriesId: Int? = null,
    val title: String,
    val missingLanguages: List<String> = emptyList(),
)

/** selectionToken is an opaque, short-lived Bazarr search result identifier. */
data class AutomationSubtitleCandidate(
    val selectionToken: String,
    val provider: String,
    val language: String,
    val score: Double? = null,
    val hearingImpaired: Boolean = false,
    val forced: Boolean = false,
    val release: String? = null,
    val originalFormat: Boolean = false,
)

data class AutomationSubtitleDownloadRequest(
    val target: AutomationSubtitleTarget,
    val candidate: AutomationSubtitleCandidate,
)

data class AutomationSubtitleDeleteRequest(
    val target: AutomationSubtitleTarget,
    val language: String,
    val path: String,
    val hearingImpaired: Boolean = false,
    val forced: Boolean = false,
)

data class TdarrLibrary(
    val id: String,
    val name: String,
    val sourcePath: String? = null,
    val transcodeEnabled: Boolean = false,
    val healthCheckEnabled: Boolean = false,
)

data class TdarrWorker(
    val nodeId: String,
    val nodeName: String,
    val workerId: String,
    val workerType: String,
    val status: String,
    val file: String? = null,
    val progressPercent: Double? = null,
)

data class TdarrNode(
    val id: String,
    val name: String,
    val online: Boolean,
    val workers: List<TdarrWorker> = emptyList(),
)

data class TdarrJob(
    val id: String,
    val file: String,
    val status: String,
    val libraryId: String? = null,
    val nodeId: String? = null,
    val progressPercent: Double? = null,
    val updatedAtMs: Long? = null,
)

enum class TdarrJobAction {
    ACCEPT,
    RETRY,
    REQUEUE,
    SKIP,
    REVIEWED,
}

data class TdarrJobActionRequest(
    val jobId: String,
    val action: TdarrJobAction,
)

data class TdarrAutomation(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

data class TdarrOverview(
    val libraries: List<TdarrLibrary>,
    val nodes: List<TdarrNode>,
    val jobs: List<TdarrJob>,
    val automations: List<TdarrAutomation>,
)

data class TdarrScanRequest(
    val libraryId: String,
    val mode: String = "scanFindNew",
    val path: String? = null,
)

data class TdarrRunAutomationRequest(
    val automationId: String,
    val libraryIds: List<String> = emptyList(),
    val nodeIds: List<String> = emptyList(),
    val executeImmediately: Boolean = false,
)

data class TdarrWorkerAction(
    val nodeId: String,
    val workerId: String,
    val cause: String = "Cancelled by Torve user",
)

enum class TdarrWorkerLimitChange { INCREASE, DECREASE }

data class TdarrWorkerLimitRequest(
    val nodeId: String,
    val workerType: String,
    val change: TdarrWorkerLimitChange,
)
