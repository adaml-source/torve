package com.torve.presentation.library

import com.torve.data.integrations.AutomationAdminClient
import com.torve.domain.integrations.AutomationAdminResult
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationMediaKind
import com.torve.domain.integrations.AutomationQueueItem
import com.torve.domain.integrations.AutomationQueueRemoval
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.MediaLifecycleEntry
import com.torve.domain.integrations.MediaLifecycleService
import com.torve.domain.integrations.MediaLifecycleState
import com.torve.domain.model.MediaType
import com.torve.domain.telemetry.AcquisitionRuntimeTelemetry
import com.torve.domain.telemetry.AcquisitionTelemetryAction
import com.torve.domain.telemetry.NoOpTelemetryEmitter
import com.torve.domain.telemetry.TelemetryEmitter
import com.torve.domain.telemetry.acquisitionCountBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

enum class AcquisitionStage {
    REQUESTED,
    APPROVED,
    SEARCHING,
    DOWNLOADING,
    PROCESSING,
    PARTIALLY_AVAILABLE,
    AVAILABLE,
    NEEDS_ATTENTION,
}

data class AcquisitionLifecycleItem(
    val tmdbId: Int?,
    val tvdbId: Int? = null,
    val mediaType: MediaType,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val requestId: Int? = null,
    val stage: AcquisitionStage,
    val statusLabel: String,
    val progressPercent: Double? = null,
    val timeLeft: String? = null,
    val errorMessage: String? = null,
    val updatedAt: String? = null,
    /** Non-secret locator used only for queue recovery actions. */
    val automationInstanceId: String? = null,
    val queueId: Long? = null,
) {
    val stableId: String
        get() = tmdbId?.let { "${mediaType.name.lowercase()}:$it" }
            ?: tvdbId?.let { "tvdb:$it" }
            ?: "${mediaType.name.lowercase()}:${title.normalizedLibraryTitle()}"

    val isActive: Boolean
        get() = stage !in setOf(AcquisitionStage.AVAILABLE, AcquisitionStage.NEEDS_ATTENTION)

    val canRetry: Boolean
        get() = stage == AcquisitionStage.NEEDS_ATTENTION &&
            automationInstanceId != null && queueId != null

    val canCancel: Boolean
        get() = stage != AcquisitionStage.AVAILABLE &&
            automationInstanceId != null && queueId != null

    val canDeleteRequest: Boolean
        get() = requestId != null
}

data class PermanentLibraryUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isConfigured: Boolean = false,
    val items: List<AcquisitionLifecycleItem> = emptyList(),
    val newlyAvailable: List<AcquisitionLifecycleItem> = emptyList(),
    val error: String? = null,
    val lastUpdatedAtMs: Long? = null,
    val actionInProgressStableId: String? = null,
    val actionMessage: String? = null,
) {
    val activeItems: List<AcquisitionLifecycleItem>
        get() = items.filter { it.stage != AcquisitionStage.AVAILABLE }
    val recentlyAvailable: List<AcquisitionLifecycleItem>
        get() = items.filter { it.stage == AcquisitionStage.AVAILABLE }.take(12)
}

/**
 * Consumer-facing acquisition state shared by TV, mobile and desktop.
 * Seerr supplies household request state, while Sonarr/Radarr are consulted
 * only for progress, ETA and actionable errors. API keys never enter UI state.
 */
class PermanentLibraryViewModel(
    private val lifecycleService: MediaLifecycleService,
    private val instanceRepository: AutomationInstanceRepository,
    private val adminClient: AutomationAdminClient,
    private val telemetry: TelemetryEmitter = NoOpTelemetryEmitter(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val _state = MutableStateFlow(PermanentLibraryUiState())
    val state: StateFlow<PermanentLibraryUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var previousStages: Map<String, AcquisitionStage>? = null

    init {
        refresh(showLoading = true)
    }

    fun startPolling(intervalMs: Long = 15_000L) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(intervalMs.coerceAtLeast(5_000L))
                refresh()
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh(showLoading: Boolean = false) {
        if (_state.value.isRefreshing) return
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = it.isLoading || showLoading,
                    isRefreshing = true,
                    error = null,
                )
            }
            val result = runCatching { loadSnapshot() }
            result.onSuccess { snapshot ->
                val currentStages = snapshot.items.associate { it.stableId to it.stage }
                val transitions = previousStages?.let { before ->
                    snapshot.items.mapNotNull { item ->
                        val previous = before[item.stableId] ?: return@mapNotNull null
                        if (previous == item.stage) null else previous to item.stage
                    }
                }.orEmpty()
                val available = previousStages?.let { before ->
                    snapshot.items.filter { item ->
                        item.stage == AcquisitionStage.AVAILABLE &&
                            before[item.stableId] != AcquisitionStage.AVAILABLE
                    }
                }.orEmpty()
                previousStages = currentStages
                val current = _state.value
                val updatedAtMs = Clock.System.now().toEpochMilliseconds()
                _state.value = snapshot.copy(
                    isLoading = false,
                    isRefreshing = false,
                    newlyAvailable = available,
                    lastUpdatedAtMs = updatedAtMs,
                    actionInProgressStableId = current.actionInProgressStableId,
                    actionMessage = current.actionMessage,
                )
                AcquisitionRuntimeTelemetry.recordRefreshSuccess(
                    activeItems = snapshot.activeItems.size,
                    attentionItems = snapshot.items.count { it.stage == AcquisitionStage.NEEDS_ATTENTION },
                    stageTransitions = transitions.size,
                    becameAvailable = available.size,
                    updatedAtMs = updatedAtMs,
                )
                emitTelemetry(
                    event = "acquisition_snapshot_refreshed",
                    attributes = mapOf(
                        "active_count" to acquisitionCountBucket(snapshot.activeItems.size),
                        "attention_count" to acquisitionCountBucket(
                            snapshot.items.count { it.stage == AcquisitionStage.NEEDS_ATTENTION },
                        ),
                        "newly_available_count" to acquisitionCountBucket(available.size),
                    ),
                )
                transitions.forEach { (from, to) ->
                    emitTelemetry(
                        event = "acquisition_stage_changed",
                        attributes = mapOf(
                            "from" to from.name.lowercase(),
                            "to" to to.name.lowercase(),
                        ),
                    )
                }
            }.onFailure {
                val updatedAtMs = Clock.System.now().toEpochMilliseconds()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = "Library status could not be refreshed. Existing items are still shown.",
                    )
                }
                AcquisitionRuntimeTelemetry.recordRefreshFailure(updatedAtMs)
                emitTelemetry(
                    event = "acquisition_snapshot_failed",
                    attributes = mapOf("failure_category" to "unavailable_or_invalid_response"),
                )
            }
        }
    }

    fun acknowledgeAvailable(stableId: String) {
        _state.update { state ->
            state.copy(newlyAvailable = state.newlyAvailable.filterNot { it.stableId == stableId })
        }
    }

    fun clearActionMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    fun retryAcquisition(stableId: String) {
        runQueueAction(stableId, QueueRecoveryAction.RETRY)
    }

    fun cancelAcquisition(stableId: String) {
        runQueueAction(stableId, QueueRecoveryAction.CANCEL)
    }

    /** Removes the Seerr request record, never an already acquired media file. */
    fun deleteRequest(stableId: String) {
        if (_state.value.actionInProgressStableId != null) return
        val item = _state.value.items.firstOrNull { it.stableId == stableId } ?: return
        val requestId = item.requestId ?: return
        scope.launch {
            _state.update {
                it.copy(
                    actionInProgressStableId = stableId,
                    actionMessage = null,
                )
            }
            val removed = runCatching { lifecycleService.deleteRequest(requestId) }.getOrDefault(false)
            _state.update { state ->
                if (removed) {
                    state.copy(
                        items = state.items.filterNot { it.stableId == stableId },
                        newlyAvailable = state.newlyAvailable.filterNot { it.stableId == stableId },
                        actionInProgressStableId = null,
                        actionMessage = "Request removed. Downloaded files were kept.",
                    )
                } else {
                    state.copy(
                        actionInProgressStableId = null,
                        actionMessage = "The library request could not be removed.",
                    )
                }
            }
            if (removed) refresh()
        }
    }

    private fun runQueueAction(stableId: String, action: QueueRecoveryAction) {
        if (_state.value.actionInProgressStableId != null) return
        scope.launch {
            val telemetryAction = action.toTelemetryAction()
            val requestTime = Clock.System.now().toEpochMilliseconds()
            AcquisitionRuntimeTelemetry.recordActionRequested(telemetryAction, requestTime)
            emitTelemetry(
                event = "acquisition_action",
                attributes = mapOf(
                    "action" to telemetryAction.name.lowercase(),
                    "outcome" to "requested",
                ),
            )
            val item = _state.value.items.firstOrNull { it.stableId == stableId }
            val instanceId = item?.automationInstanceId
            val queueId = item?.queueId
            if (item == null || instanceId == null || queueId == null) {
                AcquisitionRuntimeTelemetry.recordActionResult(
                    action = telemetryAction,
                    succeeded = false,
                    updatedAtMs = Clock.System.now().toEpochMilliseconds(),
                )
                emitTelemetry(
                    event = "acquisition_action",
                    attributes = mapOf(
                        "action" to telemetryAction.name.lowercase(),
                        "outcome" to "failed",
                        "failure_category" to "stale_queue_item",
                    ),
                )
                _state.update { it.copy(actionMessage = "This download is no longer in the automation queue. Refreshing status.") }
                refresh()
                return@launch
            }
            _state.update {
                it.copy(actionInProgressStableId = stableId, actionMessage = null)
            }
            val result = runCatching {
                val instance = instanceRepository.list().firstOrNull { it.id == instanceId }
                    ?: error("Automation connection is no longer configured")
                val apiKey = instanceRepository.apiKey(instance).orEmpty()
                require(apiKey.isNotBlank()) { "Automation credentials are unavailable" }
                when (action) {
                    QueueRecoveryAction.RETRY -> adminClient.retryQueueItem(instance, apiKey, queueId)
                    QueueRecoveryAction.CANCEL -> adminClient.removeQueueItem(
                        instance = instance,
                        apiKey = apiKey,
                        queueId = queueId,
                        removal = AutomationQueueRemoval(
                            removeFromDownloadClient = true,
                            blocklistRelease = false,
                            searchAgain = false,
                        ),
                    )
                }
            }.getOrElse {
                AutomationAdminResult.Failure(
                    code = com.torve.domain.integrations.AutomationAdminErrorCode.UNREACHABLE,
                    message = "The automation service could not be reached.",
                    retryable = true,
                )
            }
            val message = when (result) {
                is AutomationAdminResult.Success -> when (action) {
                    QueueRecoveryAction.RETRY -> "Retry requested for ${item.title}."
                    QueueRecoveryAction.CANCEL -> "Download cancelled for ${item.title}."
                }
                is AutomationAdminResult.Failure -> result.message.ifBlank {
                    "The download action could not be completed."
                }
            }
            val succeeded = result is AutomationAdminResult.Success
            AcquisitionRuntimeTelemetry.recordActionResult(
                action = telemetryAction,
                succeeded = succeeded,
                updatedAtMs = Clock.System.now().toEpochMilliseconds(),
            )
            emitTelemetry(
                event = "acquisition_action",
                attributes = buildMap {
                    put("action", telemetryAction.name.lowercase())
                    put("outcome", if (succeeded) "succeeded" else "failed")
                    if (result is AutomationAdminResult.Failure) {
                        put("failure_category", result.code.name.lowercase())
                    }
                },
            )
            _state.update {
                it.copy(actionInProgressStableId = null, actionMessage = message)
            }
            if (result is AutomationAdminResult.Success) refresh()
        }
    }

    private fun emitTelemetry(event: String, attributes: Map<String, String>) {
        runCatching { telemetry.emit(event, attributes) }
    }

    private suspend fun loadSnapshot(): PermanentLibraryUiState = coroutineScope {
        val configured = async { runCatching { lifecycleService.isConfigured() }.getOrDefault(false) }
        val entries = async { lifecycleService.listRecent(60) }
        val queues = async { loadQueueSnapshots() }
        val lifecycleEntries = entries.await()
        val queueEntries = queues.await()
        val merged = lifecycleEntries.map { entry ->
            entry.mergeWith(queueEntries.bestMatch(entry))
        }.toMutableList()

        queueEntries
            .filter { queue -> merged.none { it.matches(queue) } }
            .mapTo(merged, QueueSnapshot::toLifecycleItem)

        PermanentLibraryUiState(
            isConfigured = configured.await() || queueEntries.isNotEmpty(),
            items = merged.sortedWith(
                compareBy<AcquisitionLifecycleItem> { stageRank(it.stage) }
                    .thenByDescending { it.updatedAt.orEmpty() },
            ),
        )
    }

    private suspend fun loadQueueSnapshots(): List<QueueSnapshot> = coroutineScope {
        val instances = runCatching { instanceRepository.list() }.getOrDefault(emptyList())
            .filter {
                it.enabled && it.serviceType in setOf(AutomationServiceType.SONARR, AutomationServiceType.RADARR)
            }
        instances.map { instance ->
            async { loadQueueFor(instance) }
        }.awaitAll().flatten()
    }

    private suspend fun loadQueueFor(instance: AutomationInstance): List<QueueSnapshot> = coroutineScope {
        val apiKey = runCatching { instanceRepository.apiKey(instance).orEmpty() }.getOrDefault("")
        if (apiKey.isBlank()) return@coroutineScope emptyList()
        val libraryResult = async { adminClient.listLibrary(instance, apiKey) }
        val queueResult = async { adminClient.queue(instance, apiKey) }
        val library = (libraryResult.await() as? AutomationAdminResult.Success)?.value.orEmpty()
        val queue = (queueResult.await() as? AutomationAdminResult.Success)?.value.orEmpty()
        val libraryById = library.associateBy(AutomationLibraryItem::id)
        queue.map { row ->
            QueueSnapshot(
                instance = instance,
                queue = row,
                libraryItem = row.mediaId?.let(libraryById::get),
            )
        }
    }
}

private data class QueueSnapshot(
    val instance: AutomationInstance,
    val queue: AutomationQueueItem,
    val libraryItem: AutomationLibraryItem?,
) {
    val mediaType: MediaType
        get() = when (queue.mediaKind ?: libraryItem?.kind) {
            AutomationMediaKind.SERIES, AutomationMediaKind.EPISODE -> MediaType.SERIES
            else -> MediaType.MOVIE
        }

    fun toLifecycleItem(): AcquisitionLifecycleItem {
        val library = libraryItem
        val error = queue.errorMessage?.takeIf { it.isNotBlank() }
        val stage = when {
            error != null -> AcquisitionStage.NEEDS_ATTENTION
            queue.progressPercent != null -> AcquisitionStage.DOWNLOADING
            queue.status.contains("download", ignoreCase = true) -> AcquisitionStage.DOWNLOADING
            else -> AcquisitionStage.SEARCHING
        }
        val tmdbId = if (mediaType == MediaType.MOVIE) library?.externalId else null
        val tvdbId = if (mediaType == MediaType.SERIES) library?.externalId else null
        return AcquisitionLifecycleItem(
            tmdbId = tmdbId,
            tvdbId = tvdbId,
            mediaType = mediaType,
            title = library?.title ?: queue.title,
            year = library?.year,
            overview = library?.overview,
            posterUrl = library?.posterUrl,
            stage = stage,
            statusLabel = queueStatusLabel(queue, error),
            progressPercent = queue.progressPercent,
            timeLeft = queue.timeLeft,
            errorMessage = error,
            automationInstanceId = instance.id,
            queueId = queue.id,
        )
    }
}

private fun MediaLifecycleEntry.mergeWith(queue: QueueSnapshot?): AcquisitionLifecycleItem {
    val queueError = queue?.queue?.errorMessage?.takeIf { it.isNotBlank() }
    val stage = when {
        queueError != null -> AcquisitionStage.NEEDS_ATTENTION
        status.state == MediaLifecycleState.AVAILABLE -> AcquisitionStage.AVAILABLE
        status.state == MediaLifecycleState.PARTIALLY_AVAILABLE -> AcquisitionStage.PARTIALLY_AVAILABLE
        queue?.queue?.progressPercent != null -> AcquisitionStage.DOWNLOADING
        queue != null && queue.queue.status.contains("download", ignoreCase = true) -> AcquisitionStage.DOWNLOADING
        queue != null -> AcquisitionStage.SEARCHING
        status.state == MediaLifecycleState.PROCESSING -> AcquisitionStage.PROCESSING
        status.state == MediaLifecycleState.APPROVED -> AcquisitionStage.APPROVED
        status.state == MediaLifecycleState.PENDING_APPROVAL -> AcquisitionStage.REQUESTED
        status.state in setOf(
            MediaLifecycleState.DECLINED,
            MediaLifecycleState.FAILED,
            MediaLifecycleState.UNKNOWN,
            MediaLifecycleState.DELETED,
        ) -> AcquisitionStage.NEEDS_ATTENTION
        else -> AcquisitionStage.REQUESTED
    }
    val label = when {
        queue != null -> queueStatusLabel(queue.queue, queueError)
        stage == AcquisitionStage.AVAILABLE -> "Available to play"
        stage == AcquisitionStage.PARTIALLY_AVAILABLE -> "Partially available"
        stage == AcquisitionStage.PROCESSING -> "Processing for your library"
        stage == AcquisitionStage.APPROVED -> "Approved · waiting for download"
        stage == AcquisitionStage.REQUESTED -> "Requested · waiting for approval"
        status.state == MediaLifecycleState.DECLINED -> "Request declined"
        status.state == MediaLifecycleState.DELETED -> "Removed from library"
        else -> "Needs attention"
    }
    return AcquisitionLifecycleItem(
        tmdbId = status.tmdbId,
        tvdbId = tvdbId,
        mediaType = status.mediaType,
        title = title,
        year = year,
        overview = overview,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        rating = rating,
        requestId = status.requestId,
        stage = stage,
        statusLabel = label,
        progressPercent = queue?.queue?.progressPercent,
        timeLeft = queue?.queue?.timeLeft,
        errorMessage = queueError,
        updatedAt = status.updatedAt,
        automationInstanceId = queue?.instance?.id,
        queueId = queue?.queue?.id,
    )
}

private enum class QueueRecoveryAction { RETRY, CANCEL }

private fun QueueRecoveryAction.toTelemetryAction(): AcquisitionTelemetryAction = when (this) {
    QueueRecoveryAction.RETRY -> AcquisitionTelemetryAction.RETRY
    QueueRecoveryAction.CANCEL -> AcquisitionTelemetryAction.CANCEL
}

private fun List<QueueSnapshot>.bestMatch(entry: MediaLifecycleEntry): QueueSnapshot? {
    return firstOrNull { queue ->
        when (entry.status.mediaType) {
            MediaType.MOVIE -> queue.mediaType == MediaType.MOVIE &&
                queue.libraryItem?.externalId == entry.status.tmdbId
            MediaType.SERIES -> queue.mediaType == MediaType.SERIES &&
                entry.tvdbId != null && queue.libraryItem?.externalId == entry.tvdbId
            else -> false
        }
    } ?: firstOrNull { queue ->
        queue.mediaType == entry.status.mediaType &&
            (queue.libraryItem?.title ?: queue.queue.title).normalizedLibraryTitle() ==
            entry.title.normalizedLibraryTitle()
    }
}

private fun AcquisitionLifecycleItem.matches(queue: QueueSnapshot): Boolean {
    if (mediaType != queue.mediaType) return false
    val externalId = queue.libraryItem?.externalId
    if (mediaType == MediaType.MOVIE && tmdbId != null && tmdbId == externalId) return true
    if (mediaType == MediaType.SERIES && tvdbId != null && tvdbId == externalId) return true
    return title.normalizedLibraryTitle() ==
        (queue.libraryItem?.title ?: queue.queue.title).normalizedLibraryTitle()
}

private fun queueStatusLabel(queue: AutomationQueueItem, error: String?): String {
    if (error != null) return "Needs attention · $error"
    val progress = queue.progressPercent?.let { "${it.toInt()}%" }
    return listOfNotNull(
        if (progress != null) "Downloading $progress" else queue.status.humanizeQueueStatus(),
        queue.timeLeft?.takeIf { it.isNotBlank() }?.let { "$it remaining" },
    ).joinToString(" · ")
}

private fun String.humanizeQueueStatus(): String = when {
    contains("download", ignoreCase = true) -> "Downloading"
    contains("delay", ignoreCase = true) -> "Waiting for download client"
    contains("queue", ignoreCase = true) -> "Queued"
    isBlank() || equals("unknown", ignoreCase = true) -> "Searching for a release"
    else -> replace('_', ' ').replaceFirstChar(Char::uppercase)
}

private fun stageRank(stage: AcquisitionStage): Int = when (stage) {
    AcquisitionStage.NEEDS_ATTENTION -> 0
    AcquisitionStage.DOWNLOADING -> 1
    AcquisitionStage.SEARCHING -> 2
    AcquisitionStage.PROCESSING -> 3
    AcquisitionStage.PARTIALLY_AVAILABLE -> 4
    AcquisitionStage.APPROVED -> 5
    AcquisitionStage.REQUESTED -> 6
    AcquisitionStage.AVAILABLE -> 7
}

private fun String.normalizedLibraryTitle(): String = lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
