package com.torve.presentation.integrations

import com.torve.data.integrations.AutomationAdminClient
import com.torve.domain.integrations.AutomationAddMediaRequest
import com.torve.domain.integrations.AutomationAdminResult
import com.torve.domain.integrations.AutomationCapability
import com.torve.domain.integrations.AutomationIndexer
import com.torve.domain.integrations.AutomationIndexerCreateRequest
import com.torve.domain.integrations.AutomationInstance
import com.torve.domain.integrations.AutomationInstanceRepository
import com.torve.domain.integrations.AutomationLibraryItem
import com.torve.domain.integrations.AutomationMediaKind
import com.torve.domain.integrations.AutomationQualityProfile
import com.torve.domain.integrations.AutomationQueueItem
import com.torve.domain.integrations.AutomationQueueRemoval
import com.torve.domain.integrations.AutomationRelease
import com.torve.domain.integrations.AutomationReleaseQuery
import com.torve.domain.integrations.AutomationRootFolder
import com.torve.domain.integrations.AutomationServiceType
import com.torve.domain.integrations.AutomationSubtitleCandidate
import com.torve.domain.integrations.AutomationSubtitleDownloadRequest
import com.torve.domain.integrations.AutomationSubtitleTarget
import com.torve.domain.integrations.TdarrOverview
import com.torve.domain.integrations.TdarrJobActionRequest
import com.torve.domain.integrations.TdarrRunAutomationRequest
import com.torve.domain.integrations.TdarrScanRequest
import com.torve.domain.integrations.TdarrWorkerAction
import com.torve.domain.integrations.TdarrWorkerLimitRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AutomationAdminSection {
    LIBRARY,
    RELEASES,
    QUEUE,
    INDEXERS,
    SUBTITLES,
    TDARR,
}

enum class AutomationConfirmationKind {
    REMOVE_QUEUE_ITEM,
    DELETE_INDEXER,
    CANCEL_TDARR_WORKER,
}

data class AutomationAdminConfirmation(
    val kind: AutomationConfirmationKind,
    val title: String,
    val description: String,
    val itemId: Long? = null,
    val nodeId: String? = null,
    val workerId: String? = null,
)

data class AutomationAdministrationUiState(
    val instances: List<AutomationInstance> = emptyList(),
    val selectedInstanceId: String? = null,
    val capabilities: Set<AutomationCapability> = emptySet(),
    val section: AutomationAdminSection = AutomationAdminSection.LIBRARY,
    val searchQuery: String = "",
    val lookupResults: List<AutomationLibraryItem> = emptyList(),
    val library: List<AutomationLibraryItem> = emptyList(),
    val qualityProfiles: List<AutomationQualityProfile> = emptyList(),
    val rootFolders: List<AutomationRootFolder> = emptyList(),
    val releases: List<AutomationRelease> = emptyList(),
    val queue: List<AutomationQueueItem> = emptyList(),
    val indexers: List<AutomationIndexer> = emptyList(),
    val wantedSubtitles: List<AutomationSubtitleTarget> = emptyList(),
    val selectedSubtitleTarget: AutomationSubtitleTarget? = null,
    val subtitleCandidates: List<AutomationSubtitleCandidate> = emptyList(),
    val tdarr: TdarrOverview? = null,
    val isRefreshing: Boolean = false,
    val busyAction: String? = null,
    val message: String? = null,
    val error: String? = null,
    val pendingConfirmation: AutomationAdminConfirmation? = null,
) {
    val selectedInstance: AutomationInstance?
        get() = instances.firstOrNull { it.id == selectedInstanceId }
}

/**
 * One administration state machine shared by Android mobile, TV and desktop.
 * Secrets are resolved only for the duration of a request and are never copied
 * into UI state, error messages or logs.
 */
class AutomationAdministrationViewModel(
    private val repository: AutomationInstanceRepository,
    private val adminClient: AutomationAdminClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(AutomationAdministrationUiState())
    val state: StateFlow<AutomationAdministrationUiState> = _state.asStateFlow()

    init {
        reloadInstances()
    }

    fun reloadInstances() {
        scope.launch {
            runCatching { repository.list().filter { it.enabled } }
                .onSuccess { instances ->
                    val current = _state.value.selectedInstanceId
                    val selection = current?.takeIf { id -> instances.any { it.id == id } }
                        ?: instances.firstOrNull()?.id
                    _state.update {
                        it.copy(
                            instances = instances,
                            selectedInstanceId = selection,
                            capabilities = instances.firstOrNull { instance -> instance.id == selection }
                                ?.let(adminClient::capabilities).orEmpty(),
                            error = if (instances.isEmpty()) "Add an automation connection in Integrations first" else null,
                        )
                    }
                    if (selection != null) refresh()
                }
                .onFailure {
                    _state.update { it.copy(error = "Saved automation connections could not be loaded") }
                }
        }
    }

    fun selectInstance(instanceId: String) {
        val instance = _state.value.instances.firstOrNull { it.id == instanceId } ?: return
        _state.update {
            it.copy(
                selectedInstanceId = instanceId,
                capabilities = adminClient.capabilities(instance),
                section = defaultSection(instance.serviceType),
                searchQuery = "",
                lookupResults = emptyList(),
                library = emptyList(),
                qualityProfiles = emptyList(),
                rootFolders = emptyList(),
                releases = emptyList(),
                queue = emptyList(),
                indexers = emptyList(),
                wantedSubtitles = emptyList(),
                selectedSubtitleTarget = null,
                subtitleCandidates = emptyList(),
                tdarr = null,
                message = null,
                error = null,
                pendingConfirmation = null,
            )
        }
        refresh()
    }

    fun selectSection(section: AutomationAdminSection) = _state.update {
        it.copy(section = section, message = null, error = null)
    }

    fun updateSearchQuery(value: String) = _state.update {
        it.copy(searchQuery = value.take(160), error = null)
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    fun refresh() {
        if (_state.value.isRefreshing) return
        scope.launch {
            val credentials = selectedCredentials() ?: return@launch
            val (instance, apiKey) = credentials
            _state.update { it.copy(isRefreshing = true, error = null, message = null) }
            when (instance.serviceType) {
                AutomationServiceType.SONARR,
                AutomationServiceType.RADARR -> refreshServarr(instance, apiKey)
                AutomationServiceType.PROWLARR -> applyRefreshResult(
                    adminClient.indexers(instance, apiKey),
                    update = { result -> _state.update { it.copy(indexers = result) } },
                )
                AutomationServiceType.BAZARR -> applyRefreshResult(
                    adminClient.wantedSubtitles(instance, apiKey),
                    update = { result -> _state.update { it.copy(wantedSubtitles = result) } },
                )
                AutomationServiceType.TDARR -> applyRefreshResult(
                    adminClient.tdarrOverview(instance, apiKey),
                    update = { result -> _state.update { it.copy(tdarr = result) } },
                )
            }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    fun lookupMedia() {
        val query = _state.value.searchQuery.trim()
        if (query.length < 2) {
            _state.update { it.copy(error = "Enter at least two characters") }
            return
        }
        perform(
            action = "Searching library",
            operation = { instance, key -> adminClient.lookupMedia(instance, key, query) },
            onSuccess = { results ->
                _state.update {
                    it.copy(
                        lookupResults = results.filterNot { found -> found.id > 0 },
                        message = if (results.isEmpty()) "No matching titles found" else null,
                    )
                }
            },
        )
    }

    fun addMedia(item: AutomationLibraryItem, qualityProfileId: Int, rootFolderPath: String) {
        if (qualityProfileId <= 0 || rootFolderPath.isBlank()) {
            _state.update { it.copy(error = "Select a quality profile and root folder") }
            return
        }
        perform(
            action = "Adding ${item.title}",
            successMessage = "${item.title} was added and its first search was queued",
            operation = { instance, key ->
                adminClient.addMedia(
                    instance,
                    key,
                    AutomationAddMediaRequest(item, qualityProfileId, rootFolderPath),
                )
            },
            onSuccess = { added ->
                _state.update {
                    it.copy(
                        library = (it.library.filterNot { existing -> existing.id == added.id } + added)
                            .sortedBy(AutomationLibraryItem::title),
                        lookupResults = it.lookupResults.filterNot { found ->
                            found.externalId != null && found.externalId == added.externalId
                        },
                    )
                }
            },
        )
    }

    fun searchReleases(mediaId: Int, episodeId: Int? = null) {
        if (mediaId <= 0) return
        selectSection(AutomationAdminSection.RELEASES)
        perform(
            action = "Searching releases",
            operation = { instance, key ->
                adminClient.interactiveSearch(instance, key, AutomationReleaseQuery(mediaId, episodeId))
            },
            onSuccess = { releases ->
                _state.update {
                    it.copy(
                        releases = releases.sortedWith(
                            compareByDescending<AutomationRelease> { release -> release.approved }
                                .thenByDescending { release -> release.seeders ?: -1 },
                        ),
                        message = if (releases.isEmpty()) "No releases were returned by the configured indexers" else null,
                    )
                }
            },
        )
    }

    fun activateLibraryItem(item: AutomationLibraryItem) {
        when (item.kind) {
            AutomationMediaKind.SERIES -> searchMissingEpisodes(item)
            AutomationMediaKind.MOVIE,
            AutomationMediaKind.EPISODE -> searchReleases(item.id)
        }
    }

    private fun searchMissingEpisodes(item: AutomationLibraryItem) {
        if (item.id <= 0) return
        val monitorRegularSeasons = item.requiresSeasonMonitoring()
        perform(
            action = if (monitorRegularSeasons) "Monitoring and searching ${item.title}" else "Searching missing episodes",
            successMessage = if (monitorRegularSeasons) {
                "Sonarr is monitoring regular seasons and searching missing episodes for ${item.title}"
            } else {
                "Sonarr started searching monitored missing episodes for ${item.title}"
            },
            operation = { instance, key ->
                adminClient.searchMissingEpisodes(instance, key, item.id, monitorRegularSeasons)
            },
            onSuccess = {
                if (monitorRegularSeasons) {
                    _state.update { state ->
                        state.copy(
                            library = state.library.map { existing ->
                                if (existing.id == item.id) {
                                    existing.copy(
                                        monitored = true,
                                        monitoredSeasonCount = existing.seasonCount,
                                    )
                                } else existing
                            },
                        )
                    }
                }
            },
        )
    }

    fun grabRelease(release: AutomationRelease) = perform(
        action = "Sending release to download client",
        successMessage = "Release sent to the configured download client",
        operation = { instance, key -> adminClient.grabRelease(instance, key, release) },
    )

    fun retryQueueItem(item: AutomationQueueItem) = perform(
        action = "Retrying queue item",
        successMessage = "${item.title} was sent for another grab attempt",
        operation = { instance, key -> adminClient.retryQueueItem(instance, key, item.id) },
    )

    fun requestRemoveQueueItem(item: AutomationQueueItem) = _state.update {
        it.copy(
            pendingConfirmation = AutomationAdminConfirmation(
                kind = AutomationConfirmationKind.REMOVE_QUEUE_ITEM,
                title = "Remove queued download?",
                description = "${item.title} will be removed from the queue and download client. The release can also be blocklisted before a new search.",
                itemId = item.id,
            ),
        )
    }

    fun testIndexer(indexer: AutomationIndexer) = perform(
        action = "Testing ${indexer.name}",
        successMessage = "${indexer.name} responded successfully",
        operation = { instance, key -> adminClient.testIndexer(instance, key, indexer.id) },
    )

    fun createIndexer(request: AutomationIndexerCreateRequest) = perform(
        action = "Adding ${request.name}",
        successMessage = "${request.name} added and enabled",
        operation = { instance, key -> adminClient.createIndexer(instance, key, request) },
        onSuccess = { created ->
            _state.update { state ->
                state.copy(indexers = (state.indexers.filterNot { it.id == created.id } + created).sortedBy { it.name })
            }
        },
    )

    fun setIndexerEnabled(indexer: AutomationIndexer, enabled: Boolean) = perform(
        action = if (enabled) "Enabling ${indexer.name}" else "Disabling ${indexer.name}",
        successMessage = "${indexer.name} ${if (enabled) "enabled" else "disabled"}",
        operation = { instance, key -> adminClient.setIndexerEnabled(instance, key, indexer.id, enabled) },
        onSuccess = { updated ->
            _state.update { state ->
                state.copy(indexers = state.indexers.map { if (it.id == updated.id) updated else it })
            }
        },
    )

    fun requestDeleteIndexer(indexer: AutomationIndexer) = _state.update {
        it.copy(
            pendingConfirmation = AutomationAdminConfirmation(
                kind = AutomationConfirmationKind.DELETE_INDEXER,
                title = "Delete indexer?",
                description = "${indexer.name} will be removed from this service. This cannot be undone from Torve.",
                itemId = indexer.id.toLong(),
            ),
        )
    }

    fun searchSubtitles(target: AutomationSubtitleTarget) {
        _state.update { it.copy(selectedSubtitleTarget = target, subtitleCandidates = emptyList()) }
        perform(
            action = "Searching subtitle providers",
            operation = { instance, key -> adminClient.searchSubtitles(instance, key, target) },
            onSuccess = { candidates ->
                _state.update {
                    it.copy(
                        subtitleCandidates = candidates.sortedByDescending { candidate -> candidate.score ?: -1.0 },
                        message = if (candidates.isEmpty()) "No subtitle candidates were found" else null,
                    )
                }
            },
        )
    }

    fun downloadSubtitle(target: AutomationSubtitleTarget, candidate: AutomationSubtitleCandidate) = perform(
        action = "Downloading subtitle",
        successMessage = "${candidate.language} subtitle downloaded from ${candidate.provider}",
        operation = { instance, key ->
            adminClient.downloadSubtitle(instance, key, AutomationSubtitleDownloadRequest(target, candidate))
        },
    )

    fun searchMissingSubtitle(target: AutomationSubtitleTarget, language: String) = perform(
        action = "Searching missing subtitle",
        successMessage = "Bazarr started a ${language.ifBlank { "missing language" }} search",
        operation = { instance, key -> adminClient.searchMissingSubtitle(instance, key, target, language) },
    )

    fun scanTdarrLibrary(request: TdarrScanRequest) = perform(
        action = "Starting library scan",
        successMessage = "Tdarr library scan started",
        operation = { instance, key -> adminClient.scanTdarrLibrary(instance, key, request) },
    )

    fun actOnTdarrJob(request: TdarrJobActionRequest) = perform(
        action = "Updating Tdarr job",
        successMessage = "Tdarr job updated",
        operation = { instance, key -> adminClient.actOnTdarrJob(instance, key, request) },
        onSuccess = { refresh() },
    )

    fun runTdarrAutomation(request: TdarrRunAutomationRequest) = perform(
        action = "Starting Tdarr automation",
        successMessage = "Tdarr automation queued",
        operation = { instance, key -> adminClient.runTdarrAutomation(instance, key, request) },
    )

    fun changeTdarrWorkerLimit(request: TdarrWorkerLimitRequest) = perform(
        action = "Changing worker limit",
        successMessage = "Tdarr worker limit updated",
        operation = { instance, key -> adminClient.changeTdarrWorkerLimit(instance, key, request) },
    )

    fun requestCancelTdarrWorker(action: TdarrWorkerAction) = _state.update {
        it.copy(
            pendingConfirmation = AutomationAdminConfirmation(
                kind = AutomationConfirmationKind.CANCEL_TDARR_WORKER,
                title = "Cancel active Tdarr job?",
                description = "The worker will stop its current item safely. The source media is not deleted.",
                nodeId = action.nodeId,
                workerId = action.workerId,
            ),
        )
    }

    fun dismissConfirmation() = _state.update { it.copy(pendingConfirmation = null) }

    fun confirmPendingAction(blocklistAndSearchAgain: Boolean = false) {
        val confirmation = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        when (confirmation.kind) {
            AutomationConfirmationKind.REMOVE_QUEUE_ITEM -> {
                val queueId = confirmation.itemId ?: return
                perform(
                    action = "Removing queue item",
                    successMessage = "Queue item removed",
                    operation = { instance, key ->
                        adminClient.removeQueueItem(
                            instance,
                            key,
                            queueId,
                            AutomationQueueRemoval(
                                blocklistRelease = blocklistAndSearchAgain,
                                searchAgain = blocklistAndSearchAgain,
                            ),
                        )
                    },
                    onSuccess = { _state.update { it.copy(queue = it.queue.filterNot { row -> row.id == queueId }) } },
                )
            }
            AutomationConfirmationKind.DELETE_INDEXER -> {
                val indexerId = confirmation.itemId?.toInt() ?: return
                perform(
                    action = "Deleting indexer",
                    successMessage = "Indexer deleted",
                    operation = { instance, key -> adminClient.deleteIndexer(instance, key, indexerId) },
                    onSuccess = { _state.update { it.copy(indexers = it.indexers.filterNot { row -> row.id == indexerId }) } },
                )
            }
            AutomationConfirmationKind.CANCEL_TDARR_WORKER -> {
                val nodeId = confirmation.nodeId ?: return
                val workerId = confirmation.workerId ?: return
                perform(
                    action = "Cancelling Tdarr worker",
                    successMessage = "Tdarr worker cancellation requested",
                    operation = { instance, key ->
                        adminClient.cancelTdarrWorker(instance, key, TdarrWorkerAction(nodeId, workerId))
                    },
                )
            }
        }
    }

    private suspend fun refreshServarr(instance: AutomationInstance, apiKey: String) = coroutineScope {
        val library = async { adminClient.listLibrary(instance, apiKey) }
        val profiles = async { adminClient.qualityProfiles(instance, apiKey) }
        val roots = async { adminClient.rootFolders(instance, apiKey) }
        val queue = async { adminClient.queue(instance, apiKey) }
        val indexers = async { adminClient.indexers(instance, apiKey) }
        applyRefreshResult(library.await()) { result -> _state.update { it.copy(library = result) } }
        applyRefreshResult(profiles.await()) { result -> _state.update { it.copy(qualityProfiles = result) } }
        applyRefreshResult(roots.await()) { result -> _state.update { it.copy(rootFolders = result) } }
        applyRefreshResult(queue.await()) { result -> _state.update { it.copy(queue = result) } }
        applyRefreshResult(indexers.await()) { result -> _state.update { it.copy(indexers = result) } }
    }

    private fun <T> applyRefreshResult(result: AutomationAdminResult<T>, update: (T) -> Unit) {
        when (result) {
            is AutomationAdminResult.Success -> update(result.value)
            is AutomationAdminResult.Failure -> _state.update { state ->
                if (state.error == null) state.copy(error = result.message) else state
            }
        }
    }

    private fun <T> perform(
        action: String,
        successMessage: String? = null,
        operation: suspend (AutomationInstance, String) -> AutomationAdminResult<T>,
        onSuccess: (T) -> Unit = {},
    ) {
        if (_state.value.busyAction != null) return
        scope.launch {
            val credentials = selectedCredentials() ?: return@launch
            _state.update { it.copy(busyAction = action, error = null, message = null) }
            val result = runCatching { operation(credentials.first, credentials.second) }
                .getOrElse {
                    com.torve.domain.integrations.AutomationAdminResult.Failure(
                        com.torve.domain.integrations.AutomationAdminErrorCode.UNREACHABLE,
                        "The automation service could not be reached",
                        retryable = true,
                    )
                }
            when (result) {
                is AutomationAdminResult.Success -> {
                    onSuccess(result.value)
                    _state.update { it.copy(busyAction = null, message = successMessage, error = null) }
                }
                is AutomationAdminResult.Failure -> _state.update {
                    it.copy(busyAction = null, error = result.message)
                }
            }
        }
    }

    private suspend fun selectedCredentials(): Pair<AutomationInstance, String>? {
        val instance = _state.value.selectedInstance
        if (instance == null) {
            _state.update { it.copy(error = "Select an automation connection") }
            return null
        }
        val key = runCatching { repository.apiKey(instance).orEmpty() }.getOrDefault("")
        if (instance.serviceType != AutomationServiceType.TDARR && key.isBlank()) {
            _state.update { it.copy(error = "The encrypted API key is missing; edit this connection in Integrations") }
            return null
        }
        return instance to key
    }

    private fun defaultSection(type: AutomationServiceType): AutomationAdminSection = when (type) {
        AutomationServiceType.SONARR, AutomationServiceType.RADARR -> AutomationAdminSection.LIBRARY
        AutomationServiceType.PROWLARR -> AutomationAdminSection.INDEXERS
        AutomationServiceType.BAZARR -> AutomationAdminSection.SUBTITLES
        AutomationServiceType.TDARR -> AutomationAdminSection.TDARR
    }
}

fun AutomationLibraryItem.requiresSeasonMonitoring(): Boolean =
    kind == AutomationMediaKind.SERIES &&
        (!monitored || (seasonCount != null && monitoredSeasonCount != null && monitoredSeasonCount < seasonCount))

fun AutomationLibraryItem.primaryActionLabel(): String = when (kind) {
    AutomationMediaKind.SERIES -> if (requiresSeasonMonitoring()) "Monitor seasons + search" else "Search missing"
    AutomationMediaKind.MOVIE,
    AutomationMediaKind.EPISODE -> "Find releases"
}

fun AutomationLibraryItem.statusParts(): List<String> = buildList {
    year?.let { add(it.toString()) }
    add(kind.name.lowercase())
    if (kind == AutomationMediaKind.SERIES) {
        if (!monitored) add("not monitored")
        if (seasonCount != null && monitoredSeasonCount != null) {
            add("$monitoredSeasonCount/$seasonCount seasons monitored")
        }
        if (episodeCount != null && episodeFileCount != null) {
            add("$episodeFileCount/$episodeCount episodes downloaded")
        }
    } else if (hasFile) {
        add("downloaded")
    }
}
